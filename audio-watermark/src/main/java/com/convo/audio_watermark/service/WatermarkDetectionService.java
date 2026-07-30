package com.convo.audio_watermark.service;

import com.convo.audio_watermark.dto.WatermarkDetectionResponse;
import com.convo.audio_watermark.repository.WatermarkConfigRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.sound.sampled.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Detects audio watermarks embedded by the front-end audio worklet.
 *
 * SYNCHRONIZATION (repeating-tag design): the embedder no longer runs one
 * never-repeating PRNG stream for the whole call. Instead, its watermark
 * repeats on a fixed cycle (see cycleSeconds / hopsPerCycle) — frame f's
 * pattern depends only on (f mod hopsPerCycle), never on how long the call
 * has been running. That means ANY recording at least one cycle long is
 * guaranteed to contain a full repetition somewhere in it, and this
 * detector only ever needs to search within ONE cycle's length — a fixed,
 * small window — rather than across the whole call, which is what made the
 * earlier frame-0-alignment / whole-call-search approaches either fragile
 * (needed a cooperating "reset" signal) or expensive (search cost grew with
 * meeting length).
 *
 * The search is still two-dimensional:
 * 1. cyclePos — which position within the repeat cycle the recording's
 *    first frame corresponds to. Bounded to [0, hopsPerCycle), which is
 *    small and FIXED regardless of call length (e.g. ~3750 positions for a
 *    20-second cycle at a 256-sample hop / 48kHz).
 * 2. phase — the exact sample offset within a hop the recording begins at,
 *    unchanged from before; unrelated to cycle length, still needs its own
 *    small search because pseudorandom sequences don't tolerate even
 *    small misalignment.
 *
 * As before: jumping the PRNG to a candidate cyclePos is O(1) (see
 * stateAfterDraws) rather than stepping through every preceding frame, and
 * the audio-side masking-threshold analysis for a given phase is shared
 * across every user and every cyclePos candidate tested at that phase
 * (see analyzeAudioFrames) since it depends only on the recorded samples.
 *
 * SCHEMA NOTE: this assumes each user's registered config also carries a
 * cycleSeconds value matching what the embedder used (see
 * DetectionConfigProjection.getCycleSeconds() below) — add this alongside
 * frameSize / analysisWindowSize / numBands if it isn't already present in
 * your config table/projection/DTO plumbing.
 *
 * Everything below the search itself (PN generation, masking-threshold
 * shaping, IFFT/overlap-add reconstruction, per-frame normalised
 * correlation, FFT/Hann/Bark-band helpers, audio decoding) is unchanged and
 * still mirrors the JS embedder exactly — see the per-method docs below.
 */
@Service
public class WatermarkDetectionService {

    /**
     * Minimum average per-frame normalised correlation to declare detection.
     *
     * IMPORTANT — CALIBRATION: this score is the average of PER-FRAME
     * normalised correlations at the winning alignment (see
     * scoreWithAnalysis), not a single whole-recording correlation. This has
     * not yet been calibrated against real known-good / known-bad
     * recordings. Treat 0.015 below as a placeholder only — re-run known-good
     * and known-bad test recordings with this scoring method and set both
     * thresholds from that data before relying on this in production.
     */
    private static final double DETECTION_THRESHOLD = 0.015;

    /**
     * Minimum gap between the best and second-best score required to trust
     * the winner. Without this, two close/noisy scores can flip the
     * "detected" user essentially at random. Needs the same fresh
     * calibration as DETECTION_THRESHOLD.
     */
    private static final double MIN_SCORE_MARGIN = 0.01;

    /** Coarse cyclePos stride divisor — see findBestScoresAcrossUsers. */
    private static final int COARSE_CYCLE_CANDIDATES = 200;

    /** Total coarse sample-phase candidates spread across one hop. */
    private static final int PHASE_COARSE_CANDIDATES = 8;

    /**
     * Frames used during the coarse + refine search stages (~0.4s of audio
     * at a 256-sample hop / 48kHz). Kept short for search speed — the final
     * reported score always uses the FULL recording at the winning
     * alignment (see stage 3 in findBestScoresAcrossUsers), so this only
     * affects how reliably the search LOCATES the right alignment, not the
     * quality of the final reported score.
     */
    private static final int SEARCH_FRAME_COUNT = 80;

    /** Sample rate FFmpeg resamples fallback-decoded audio to. */
    private static final int TARGET_SAMPLE_RATE = 48000;

    /** Max time to let a single ffmpeg transcode run before giving up. */
    private static final long FFMPEG_TIMEOUT_SECONDS = 60;

    private final WatermarkConfigRepository repository;

    public WatermarkDetectionService(WatermarkConfigRepository repository) {
        this.repository = repository;
    }

    /**
     * Fail fast at startup if ffmpeg isn't available in this environment,
     * rather than discovering it silently on the first non-WAV upload.
     */
    @PostConstruct
    public void verifyFfmpegAvailable() {
        try {
            Process p = new ProcessBuilder("ffmpeg", "-version").start();
            boolean finished = p.waitFor(10, TimeUnit.SECONDS);
            if (!finished || p.exitValue() != 0) {
                throw new IllegalStateException("ffmpeg check failed or timed out");
            }
        } catch (Exception e) {
            throw new IllegalStateException(
                    "ffmpeg is not available on this system — audio decoding for non-WAV formats "
                            + "(MP3/AAC/M4A/Opus) will fail. Ensure ffmpeg is installed in the "
                            + "deployment environment (see Dockerfile).", e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    public WatermarkDetectionResponse detect(MultipartFile audioFile, String sessionId)
            throws IOException, UnsupportedAudioFileException {

        // ── 1. Fetch all registered watermark configs for this meeting ───────
        List<WatermarkConfigRepository.DetectionConfigProjection> sessionConfigs = repository
                .findDetectionConfigsByMeetingCode(sessionId);
        if (sessionConfigs.isEmpty()) {
            return new WatermarkDetectionResponse(
                    null, null, sessionId, 0.0, false, 0, 0,
                    Collections.emptyMap(),
                    Collections.emptyMap(),
                    "No registered users found for watermark detection.");
        }

        // ── 2. Decode audio to float samples & extract sample rate ──────────
        DecodedAudio decoded = decodeAudioToFloatSamples(audioFile);
        float[] samples = decoded.samples;
        float sampleRate = decoded.sampleRate;

        if (samples.length == 0) {
            return new WatermarkDetectionResponse(
                    null, null, sessionId, 0.0, false, 0, sessionConfigs.size(),
                    Collections.emptyMap(),
                    Collections.emptyMap(),
                    "Audio file is empty or could not be decoded.");
        }

        // All users in the same session share the same frameSize / analysisWindowSize / numBands
        int frameSize = sessionConfigs.get(0).getFrameSize();
        int analysisSize = sessionConfigs.get(0).getAnalysisWindowSize() != null
                ? sessionConfigs.get(0).getAnalysisWindowSize() : frameSize * 2;
        int numBands = sessionConfigs.get(0).getNumBands() != null
                ? sessionConfigs.get(0).getNumBands() : 24;

        // Precompute shared resources
        float[] window = hannWindow(analysisSize);
        int[] binToBand = buildBinToBandMap(analysisSize, sampleRate, numBands);

        int hop = frameSize;
        int numFrames = samples.length / hop;

        if (numFrames == 0) {
            return new WatermarkDetectionResponse(
                    null, null, sessionId, 0.0, false, 0, sessionConfigs.size(),
                    Collections.emptyMap(),
                    Collections.emptyMap(),
                    "Audio too short for detection (need at least " + hop + " samples).");
        }

        // ── 3. Cycle-bounded sync search + score every registered user ──────
        Map<String, Double> allUserScores = findBestScoresAcrossUsers(
                samples, sessionConfigs, hop, analysisSize, numBands, sampleRate, window, binToBand);

        Map<String, String> userDisplayNames = new LinkedHashMap<>();
        for (WatermarkConfigRepository.DetectionConfigProjection config : sessionConfigs) {
            userDisplayNames.put(config.getUserId(), config.getDisplayName());
        }

        // ── 4. Find the user with the highest score, and the runner-up ──────
        String bestUserId = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        double secondBestScore = Double.NEGATIVE_INFINITY;
        for (Map.Entry<String, Double> entry : allUserScores.entrySet()) {
            double v = entry.getValue();
            if (v > bestScore) {
                secondBestScore = bestScore;
                bestScore = v;
                bestUserId = entry.getKey();
            } else if (v > secondBestScore) {
                secondBestScore = v;
            }
        }
        if (secondBestScore == Double.NEGATIVE_INFINITY) {
            secondBestScore = bestScore;
        }

        // ── 5. Apply detection threshold + minimum margin over runner-up ────
        boolean detected = bestScore >= DETECTION_THRESHOLD
                && (bestScore - secondBestScore) >= MIN_SCORE_MARGIN;

        final String finalBestUser = bestUserId;
        WatermarkConfigRepository.DetectionConfigProjection winnerConfig = sessionConfigs.stream()
                .filter(c -> c.getUserId().equals(finalBestUser))
                .findFirst()
                .orElse(sessionConfigs.get(0));

        String message = detected
                ? String.format(
                        "Watermark detected. Detected user: '%s' | score=%.6f (margin over runner-up=%.6f)",
                        winnerConfig.getDisplayName(), bestScore, bestScore - secondBestScore)
                : String.format(
                        "No watermark detected. Highest score: %.6f for user '%s' (margin over runner-up=%.6f)",
                        bestScore, winnerConfig.getDisplayName(), bestScore - secondBestScore);

        Map<String, Double> roundedScores = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : allUserScores.entrySet()) {
            roundedScores.put(e.getKey(), round4(e.getValue()));
        }

        return new WatermarkDetectionResponse(
                detected ? winnerConfig.getUserId() : null,
                detected ? winnerConfig.getDisplayName() : null,
                sessionId,
                round4(bestScore),
                detected,
                numFrames,
                sessionConfigs.size(),
                roundedScores,
                userDisplayNames,
                message);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Cycle-bounded synchronization search
    // ─────────────────────────────────────────────────────────────────────────

    /** Tracks the best (phase, cyclePos, score) found so far for one user. */
    private static class BestAlignment {
        double score = Double.NEGATIVE_INFINITY;
        int phase = 0;
        long cyclePos = 0;
    }

    /** Per-frame masking-threshold analysis of the recorded audio at a given phase. */
    private static class FrameAnalysis {
        final double[][] spreadThreshold; // [frame][band]
        FrameAnalysis(double[][] spreadThreshold) {
            this.spreadThreshold = spreadThreshold;
        }
    }

    /**
     * Scratch buffers reused across every candidate evaluated in one
     * detection request, instead of each candidate allocating (and
     * zero-initializing) its own. With thousands of candidates tested per
     * request, fresh allocation per call was the dominant cost — not the
     * actual FFT/correlation work — since {@code recon} in particular can
     * be tens of thousands of elements. Sized once to the largest frame
     * count any stage will use (the full recording, in stage 3); earlier
     * stages use a shorter prefix of the same buffer and clear only the
     * region they touch.
     */
    private static class ScratchBuffers {
        final double[] pnRe;
        final double[] pnIm;
        final double[] recon;
        ScratchBuffers(int analysisSize, int maxReconLen) {
            pnRe = new double[analysisSize];
            pnIm = new double[analysisSize];
            recon = new double[maxReconLen];
        }
    }

    private Map<String, Double> findBestScoresAcrossUsers(
            float[] samples,
            List<WatermarkConfigRepository.DetectionConfigProjection> sessionConfigs,
            int hop, int analysisSize, int numBands, float sampleRate,
            float[] window, int[] binToBand) {

        int numDetectorFrames = samples.length / hop;
        int searchFrameCount = Math.min(SEARCH_FRAME_COUNT, numDetectorFrames);

        int phaseStride = Math.max(1, hop / PHASE_COARSE_CANDIDATES);

        // Allocated ONCE for the whole request — see ScratchBuffers docs.
        int maxReconLen = numDetectorFrames * hop + analysisSize;
        ScratchBuffers buffers = new ScratchBuffers(analysisSize, maxReconLen);

        Map<String, BestAlignment> bestByUser = new HashMap<>();
        Map<String, Long> hopsPerCycleByUser = new HashMap<>();
        for (WatermarkConfigRepository.DetectionConfigProjection c : sessionConfigs) {
            bestByUser.put(c.getUserId(), new BestAlignment());
            // Each user's cycle length in hops — MUST match what their
            // embedder used (config.cycleSeconds), not a shared constant,
            // in case that's ever configured per-user.
            long hopsPerCycle = Math.max(1, Math.round((c.getCycleSeconds() * sampleRate) / hop));
            hopsPerCycleByUser.put(c.getUserId(), hopsPerCycle);
        }

        // ── Stage 1: coarse (phase, cyclePos) scan, audio analysis shared
        //    across every user and every cyclePos candidate at a given phase.
        for (int phase = 0; phase < hop; phase += phaseStride) {
            int framesAvailable = Math.min(searchFrameCount, (samples.length - phase) / hop);
            if (framesAvailable <= 0) continue;

            FrameAnalysis analysis = analyzeAudioFrames(
                    samples, phase, hop, analysisSize, numBands, window, binToBand, framesAvailable);

            for (WatermarkConfigRepository.DetectionConfigProjection c : sessionConfigs) {
                long hopsPerCycle = hopsPerCycleByUser.get(c.getUserId());
                long cycleStride = Math.max(1, hopsPerCycle / COARSE_CYCLE_CANDIDATES);

                for (long cyclePos = 0; cyclePos < hopsPerCycle; cyclePos += cycleStride) {
                    double score = scoreCandidate(
                            samples, phase, analysis, c, cyclePos, hopsPerCycle, hop, analysisSize,
                            numBands, window, binToBand, framesAvailable, buffers);

                    BestAlignment ba = bestByUser.get(c.getUserId());
                    if (score > ba.score) {
                        ba.score = score;
                        ba.phase = phase;
                        ba.cyclePos = cyclePos;
                    }
                }
            }
        }

        // ── Stage 2: local refine at full (1-sample, 1-hop) resolution
        //    around each user's best coarse candidate.
        for (WatermarkConfigRepository.DetectionConfigProjection c : sessionConfigs) {
            BestAlignment ba = bestByUser.get(c.getUserId());
            long hopsPerCycle = hopsPerCycleByUser.get(c.getUserId());
            long cycleStride = Math.max(1, hopsPerCycle / COARSE_CYCLE_CANDIDATES);

            int phaseLo = Math.max(0, ba.phase - phaseStride);
            int phaseHi = Math.min(hop - 1, ba.phase + phaseStride);
            long cycleLo = Math.max(0, ba.cyclePos - cycleStride);
            long cycleHi = Math.min(hopsPerCycle - 1, ba.cyclePos + cycleStride);

            for (int phase = phaseLo; phase <= phaseHi; phase++) {
                int framesAvailable = Math.min(searchFrameCount, (samples.length - phase) / hop);
                if (framesAvailable <= 0) continue;

                FrameAnalysis analysis = analyzeAudioFrames(
                        samples, phase, hop, analysisSize, numBands, window, binToBand, framesAvailable);

                for (long cyclePos = cycleLo; cyclePos <= cycleHi; cyclePos++) {
                    double score = scoreCandidate(
                            samples, phase, analysis, c, cyclePos, hopsPerCycle, hop, analysisSize,
                            numBands, window, binToBand, framesAvailable, buffers);
                    if (score > ba.score) {
                        ba.score = score;
                        ba.phase = phase;
                        ba.cyclePos = cyclePos;
                    }
                }
            }
        }

        // ── Stage 3: final score at the pinpointed alignment, over the
        //    WHOLE recording — this is what's actually compared against
        //    DETECTION_THRESHOLD / MIN_SCORE_MARGIN.
        Map<String, Double> finalScores = new LinkedHashMap<>();
        for (WatermarkConfigRepository.DetectionConfigProjection c : sessionConfigs) {
            BestAlignment ba = bestByUser.get(c.getUserId());
            int framesAvailable = (samples.length - ba.phase) / hop;
            if (framesAvailable <= 0) {
                finalScores.put(c.getUserId(), 0.0);
                continue;
            }

            FrameAnalysis analysis = analyzeAudioFrames(
                    samples, ba.phase, hop, analysisSize, numBands, window, binToBand, framesAvailable);
            long hopsPerCycle = hopsPerCycleByUser.get(c.getUserId());
            double score = scoreCandidate(
                    samples, ba.phase, analysis, c, ba.cyclePos, hopsPerCycle, hop, analysisSize,
                    numBands, window, binToBand, framesAvailable, buffers);

            finalScores.put(c.getUserId(), score);
        }
        return finalScores;
    }

    private double scoreCandidate(
            float[] samples, int phase, FrameAnalysis analysis,
            WatermarkConfigRepository.DetectionConfigProjection config, long cyclePos, long hopsPerCycle,
            int hop, int analysisSize, int numBands, float[] window, int[] binToBand,
            int frameCount, ScratchBuffers buffers) {

        long baseSeedState = resolveSeed(config.getSeed());
        double marginLinear = Math.pow(10.0, config.getAlpha() / 20.0);
        return scoreWithAnalysis(
                samples, phase, analysis, baseSeedState, cyclePos, hopsPerCycle, hop, analysisSize,
                numBands, window, binToBand, marginLinear, frameCount, buffers);
    }

    /**
     * Jump a mulberry32 PRNG state ahead by {@code numDraws} calls in O(1).
     *
     * mulberry32's state update is a plain linear counter increment
     * (state = (state + 0x6d2b79f5) mod 2^32) applied BEFORE each call's
     * output scrambling — the scrambling never feeds back into the counter.
     * That means the state after N calls is simply the initial state plus
     * N * increment (mod 2^32), computable directly instead of by stepping
     * through N calls one at a time. This is what lets the detector jump
     * straight to a candidate cyclePos instead of replaying every prior
     * frame in the cycle.
     */
    private long stateAfterDraws(long initialState, long numDraws) {
        long increment = 0x6d2b79f5L;
        return (initialState + numDraws * increment) & 0xFFFFFFFFL;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Watermark reconstruction + correlation — mirrors the embedder pipeline
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Analyses {@code frameCount} hop-aligned frames of the recorded audio
     * starting at sample {@code sampleOffset} ("phase"), producing the
     * per-frame masking threshold the embedder would have used to shape its
     * watermark. This depends only on the recorded samples and the phase —
     * never on which user or cyclePos is being tested — so callers compute
     * it once per phase and reuse it across every user and every cyclePos
     * candidate tested at that phase.
     *
     * WARM-UP NOTE: mirrors the embedder's zero-filled sliding analysis
     * buffer (this._analysisBuf = new Float32Array(analysisSize) in the JS
     * worklet), which slides left by one hop per frame — so the first frame
     * or two analyse a window that's partly zeros, exactly as the embedder's
     * would have been at its own startup.
     */
    private FrameAnalysis analyzeAudioFrames(
            float[] samples, int sampleOffset, int hop, int analysisSize, int numBands,
            float[] window, int[] binToBand, int frameCount) {

        double[] analysisBuf = new double[analysisSize];
        double[] audioRe = new double[analysisSize];
        double[] audioIm = new double[analysisSize];
        double[] bandEnergy = new double[numBands];
        double[] bandLogSum = new double[numBands];
        double[] bandCount = new double[numBands];
        double[] bandFlatness = new double[numBands];
        double[] bandThreshold = new double[numBands];
        double[] spreadScratch = new double[numBands];

        double[][] spreadThreshold = new double[frameCount][numBands];

        for (int f = 0; f < frameCount; f++) {
            int off = sampleOffset + f * hop;

            System.arraycopy(analysisBuf, hop, analysisBuf, 0, analysisSize - hop);
            int copyCount = Math.max(0, Math.min(hop, samples.length - off));
            for (int i = 0; i < copyCount; i++) {
                analysisBuf[analysisSize - hop + i] = samples[off + i];
            }
            for (int i = copyCount; i < hop; i++) {
                analysisBuf[analysisSize - hop + i] = 0.0;
            }

            for (int i = 0; i < analysisSize; i++) {
                audioRe[i] = analysisBuf[i] * window[i];
                audioIm[i] = 0.0;
            }
            fft(audioRe, audioIm, false);

            Arrays.fill(bandEnergy, 0.0);
            Arrays.fill(bandLogSum, 0.0);
            Arrays.fill(bandCount, 0.0);
            for (int k = 0; k < analysisSize / 2; k++) {
                double mag2 = audioRe[k] * audioRe[k] + audioIm[k] * audioIm[k];
                int b = binToBand[k];
                bandEnergy[b] += mag2;
                bandLogSum[b] += Math.log(mag2 + 1e-12);
                bandCount[b] += 1.0;
            }
            for (int b = 0; b < numBands; b++) {
                double count = Math.max(bandCount[b], 1.0);
                double geoMean = Math.exp(bandLogSum[b] / count);
                double arithMean = bandEnergy[b] / count;
                bandFlatness[b] = geoMean / (arithMean + 1e-12);
                bandEnergy[b] = arithMean;
            }
            for (int b = 0; b < numBands; b++) {
                double flat = Math.min(Math.max(bandFlatness[b], 0.0), 1.0);
                double offsetDb = 18.0 - flat * 12.0;
                double energyDb = 10.0 * Math.log10(bandEnergy[b] + 1e-12);
                bandThreshold[b] = Math.pow(10.0, (energyDb - offsetDb) / 10.0);
            }
            // 3-tap left-to-right cascading spread — mirrors the JS
            // worklet's in-place update order exactly (band b's spread uses
            // band b-1's already-spread value and band b+1's raw value).
            for (int b = 0; b < numBands; b++) {
                double left = b > 0 ? spreadScratch[b - 1] : bandThreshold[b];
                double right = b < numBands - 1 ? bandThreshold[b + 1] : bandThreshold[b];
                spreadScratch[b] = 0.5 * bandThreshold[b] + 0.25 * left + 0.25 * right;
            }
            System.arraycopy(spreadScratch, 0, spreadThreshold[f], 0, numBands);
        }

        return new FrameAnalysis(spreadThreshold);
    }

    /**
     * Reconstructs the predicted watermark-only signal for one (seed,
     * phase, cyclePos) candidate using the precomputed masking analysis,
     * then scores it against the actual recorded audio via per-frame
     * normalised cross-correlation, averaged across frames.
     *
     * CYCLE WRAP: each frame's PRNG state is derived fresh from
     * ((cyclePos + f) mod hopsPerCycle) — mirroring the embedder's
     * _randForFrame exactly — rather than letting one PRNG state advance
     * continuously across the whole scoring loop. Without this, a
     * recording that straddles a cycle boundary (its true start is near
     * the END of a cycle and runs into the next repeat) would score
     * correctly only up to the boundary, then compare against the wrong,
     * "unwrapped" continuation for every frame after it — dragging the
     * average down for no reason related to whether the seed is actually
     * correct.
     *
     * Scoring is per-frame (not one global correlation) because the
     * masking-driven shaping makes every candidate's reconstruction louder
     * exactly when the real audio is louder, regardless of whether the seed
     * is correct — a global correlation would pick up on that shared
     * loudness envelope as a false signal. Normalising each frame by its own
     * local energy isolates the actual noise-pattern match instead.
     */
    private double scoreWithAnalysis(
            float[] samples, int sampleOffset, FrameAnalysis analysis,
            long baseSeedState, long cyclePos, long hopsPerCycle,
            int hop, int analysisSize, int numBands, float[] window, int[] binToBand,
            double marginLinear, int frameCount, ScratchBuffers buffers) {

        double[] pnRe = buffers.pnRe;
        double[] pnIm = buffers.pnIm;
        double[] recon = buffers.recon;
        int reconLen = frameCount * hop + analysisSize;
        // Only clear the portion this call actually uses — reused across
        // every candidate, so stale values from a previous (possibly
        // longer) call must not leak in, but there's no need to touch the
        // rest of a buffer sized for the largest call this request makes.
        Arrays.fill(recon, 0, reconLen, 0.0);

        for (int f = 0; f < frameCount; f++) {
            int off = f * hop;

            // Fresh state per frame, wrapped to this frame's position
            // within the cycle — matches _randForFrame in the JS embedder.
            long cyclePosF = (cyclePos + f) % hopsPerCycle;
            long[] prngState = { stateAfterDraws(baseSeedState, cyclePosF * (long) analysisSize) };

            for (int k = 0; k < analysisSize; k++) {
                pnRe[k] = mulberry32Next(prngState) * 2.0 - 1.0;
                pnIm[k] = 0.0;
            }
            fft(pnRe, pnIm, false);

            double[] spreadThreshold = analysis.spreadThreshold[f];
            for (int k = 0; k < analysisSize / 2; k++) {
                int b = binToBand[k];
                double mag = Math.sqrt(pnRe[k] * pnRe[k] + pnIm[k] * pnIm[k]) + 1e-12;
                double gain = (Math.sqrt(spreadThreshold[b]) * marginLinear) / mag;
                pnRe[k] *= gain;
                pnIm[k] *= gain;
                int mirror = (analysisSize - k) % analysisSize;
                pnRe[mirror] = pnRe[k];
                pnIm[mirror] = -pnIm[k];
            }

            fft(pnRe, pnIm, true);
            for (int i = 0; i < analysisSize; i++) {
                pnRe[i] *= window[i];
            }

            for (int i = 0; i < analysisSize && (off + i) < recon.length; i++) {
                recon[off + i] += pnRe[i];
            }
        }

        double totalNormCorr = 0.0;
        int scoredFrames = 0;
        for (int f = 0; f < frameCount; f++) {
            int off = f * hop;
            int sOff = sampleOffset + off;
            double corr = 0.0, audioPower = 0.0, reconPower = 0.0;
            for (int i = 0; i < hop && (sOff + i) < samples.length; i++) {
                double a = samples[sOff + i];
                double r = recon[off + i];
                corr += a * r;
                audioPower += a * a;
                reconPower += r * r;
            }
            double denom = Math.sqrt(audioPower * reconPower);
            if (denom > 1e-9) {
                totalNormCorr += corr / denom;
                scoredFrames++;
            }
        }
        return scoredFrames > 0 ? totalNormCorr / scoredFrames : 0.0;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // FFT — exact port of the JS worklet's radix-2 iterative FFT
    // ─────────────────────────────────────────────────────────────────────────

    private static void fft(double[] re, double[] im, boolean invert) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) {
                j ^= bit;
            }
            j ^= bit;
            if (i < j) {
                double tmp = re[i]; re[i] = re[j]; re[j] = tmp;
                tmp = im[i]; im[i] = im[j]; im[j] = tmp;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = (2.0 * Math.PI / len) * (invert ? -1 : 1);
            double wr = Math.cos(ang);
            double wi = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double curWr = 1.0, curWi = 0.0;
                for (int j = 0; j < len / 2; j++) {
                    double ur = re[i + j], ui = im[i + j];
                    double vr = re[i + j + len / 2] * curWr - im[i + j + len / 2] * curWi;
                    double vi = re[i + j + len / 2] * curWi + im[i + j + len / 2] * curWr;
                    re[i + j] = ur + vr;
                    im[i + j] = ui + vi;
                    re[i + j + len / 2] = ur - vr;
                    im[i + j + len / 2] = ui - vi;
                    double nWr = curWr * wr - curWi * wi;
                    double nWi = curWr * wi + curWi * wr;
                    curWr = nWr;
                    curWi = nWi;
                }
            }
        }
        if (invert) {
            for (int i = 0; i < n; i++) {
                re[i] /= n;
                im[i] /= n;
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Hann window — mirrors JS _hannWindow(n)
    // ─────────────────────────────────────────────────────────────────────────

    private static float[] hannWindow(int n) {
        float[] w = new float[n];
        for (int i = 0; i < n; i++) {
            w[i] = (float) (0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (n - 1)));
        }
        return w;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Bark-band mapping — mirrors JS _hzToBark and _buildBinToBandMap
    // ─────────────────────────────────────────────────────────────────────────

    private static double hzToBark(double hz) {
        return 13.0 * Math.atan(0.00076 * hz) + 3.5 * Math.atan(Math.pow(hz / 7500.0, 2));
    }

    private static int[] buildBinToBandMap(int n, float sampleRate, int numBands) {
        int[] map = new int[n];
        double nyquistBark = hzToBark(sampleRate / 2.0);
        for (int k = 0; k < n; k++) {
            double hz = (double) k * sampleRate / n;
            double bark = hzToBark(hz);
            int band = (int) Math.floor((bark / nyquistBark) * numBands);
            if (band >= numBands) band = numBands - 1;
            if (band < 0) band = 0;
            map[k] = band;
        }
        return map;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Audio decoding — tries AudioSystem (WAV/AIFF/AU) first, falls back to
    // FFmpeg for everything else (MP3, AAC, M4A, Opus, ...)
    // ─────────────────────────────────────────────────────────────────────────

    private static class DecodedAudio {
        final float[] samples;
        final float sampleRate;
        DecodedAudio(float[] samples, float sampleRate) {
            this.samples = samples;
            this.sampleRate = sampleRate;
        }
    }

    private DecodedAudio decodeAudioToFloatSamples(MultipartFile audioFile)
            throws IOException, UnsupportedAudioFileException {

        byte[] fileBytes = audioFile.getBytes();

        try {
            return decodeUsingAudioSystem(fileBytes);
        } catch (UnsupportedAudioFileException e) {
            return decodeUsingFfmpeg(fileBytes, audioFile.getOriginalFilename());
        }
    }

    private DecodedAudio decodeUsingAudioSystem(byte[] fileBytes)
            throws IOException, UnsupportedAudioFileException {

        try (AudioInputStream ais = AudioSystem.getAudioInputStream(
                new BufferedInputStream(new ByteArrayInputStream(fileBytes)))) {

            AudioFormat src = ais.getFormat();
            float sampleRate = src.getSampleRate();

            AudioFormat pcmFormat = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    sampleRate,
                    16,
                    1,
                    2,
                    sampleRate,
                    false);

            try (AudioInputStream pcm = AudioSystem.getAudioInputStream(pcmFormat, ais)) {
                return new DecodedAudio(pcmBytesToFloats(pcm.readAllBytes()), sampleRate);
            }
        }
    }

    private DecodedAudio decodeUsingFfmpeg(byte[] fileBytes, String originalFilename) throws IOException {

        Path inputPath = Files.createTempFile("watermark-in-", extractExtension(originalFilename));
        Path outputPath = Files.createTempFile("watermark-out-", ".wav");

        try {
            Files.write(inputPath, fileBytes);

            List<String> command = List.of(
                    "ffmpeg",
                    "-y",
                    "-i", inputPath.toString(),
                    "-ac", "1",
                    "-ar", String.valueOf(TARGET_SAMPLE_RATE),
                    "-f", "wav",
                    outputPath.toString());

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            String ffmpegOutput;
            try (InputStream is = process.getInputStream()) {
                ffmpegOutput = new String(is.readAllBytes());
            }

            boolean finished;
            try {
                finished = process.waitFor(FFMPEG_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for ffmpeg", ie);
            }

            if (!finished) {
                process.destroyForcibly();
                throw new IOException("ffmpeg timed out while decoding audio");
            }
            if (process.exitValue() != 0) {
                throw new IOException("ffmpeg failed to decode audio (exit=" + process.exitValue()
                        + "): " + ffmpegOutput);
            }

            byte[] wavBytes = Files.readAllBytes(outputPath);

            try (AudioInputStream ais = AudioSystem.getAudioInputStream(
                    new BufferedInputStream(new ByteArrayInputStream(wavBytes)))) {

                AudioFormat src = ais.getFormat();
                float sampleRate = src.getSampleRate();

                AudioFormat pcmFormat = new AudioFormat(
                        AudioFormat.Encoding.PCM_SIGNED,
                        sampleRate, 16, 1, 2, sampleRate, false);

                try (AudioInputStream pcm = AudioSystem.getAudioInputStream(pcmFormat, ais)) {
                    return new DecodedAudio(pcmBytesToFloats(pcm.readAllBytes()), sampleRate);
                }
            } catch (UnsupportedAudioFileException uafe) {
                throw new IOException("ffmpeg produced a WAV file that could not be read back", uafe);
            }

        } finally {
            Files.deleteIfExists(inputPath);
            Files.deleteIfExists(outputPath);
        }
    }

    private String extractExtension(String originalFilename) {
        if (originalFilename == null) return ".tmp";
        int dot = originalFilename.lastIndexOf('.');
        if (dot < 0 || dot == originalFilename.length() - 1) return ".tmp";
        return originalFilename.substring(dot);
    }

    private float[] pcmBytesToFloats(byte[] bytes) {
        int n = bytes.length / 2;
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            short s = (short) ((bytes[2 * i + 1] << 8) | (bytes[2 * i] & 0xFF));
            out[i] = s / 32768.0f;
        }
        return out;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PN generation — exact Java port of the front-end JS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Resolve a seed string to an unsigned 32-bit long, exactly as the JS
     * worklet does. The JS worklet ALWAYS hashes string seeds via
     * hashString() — there is no "numeric string -> raw integer" branch.
     */
    private long resolveSeed(String seed) {
        return hashString(seed);
    }

    private long hashString(String str) {
        long hash = 5381L;
        for (int i = 0; i < str.length(); i++)
            hash = ((((hash & 0xFFFFFFFFL) << 5) + hash) + str.charAt(i)) & 0xFFFFFFFFL;
        return hash;
    }

    /** One step of mulberry32 — exact port of JS mulberry32(). */
    private double mulberry32Next(long[] state) {
        state[0] = (state[0] + 0x6d2b79f5L) & 0xFFFFFFFFL;
        long s = state[0];
        long t = imul32(s ^ (s >>> 15), 1L | s);
        t = (t + imul32(t ^ (t >>> 7), 61L | t)) ^ t;
        t = (t ^ (t >>> 14)) & 0xFFFFFFFFL;
        return t / 4294967296.0;
    }

    private long imul32(long a, long b) {
        return (a & 0xFFFFFFFFL) * (b & 0xFFFFFFFFL) & 0xFFFFFFFFL;
    }

    private double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}