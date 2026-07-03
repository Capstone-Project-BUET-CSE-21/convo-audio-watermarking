package com.convo.audio_watermark.service;

import com.convo.audio_watermark.dto.WatermarkDetectionResponse;
import com.convo.audio_watermark.repository.WatermarkConfigRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.sound.sampled.*;
import java.io.*;
import java.util.*;

/**
 * Detects audio watermarks embedded by the front-end audio worklet.
 *
 * Detection pipeline (mirrors the front-end embedder exactly):
 *
 * 1. Look up every user registered in the session from the database.
 * 2. Decode the uploaded audio to normalised float PCM samples.
 * 3. For each user in the session:
 *    a. Create a streaming PRNG from their seed (same as the embedder).
 *    b. Process hop-aligned frames using the same analysis window size:
 *       - Window the audio with a Hann window.
 *       - FFT the windowed audio.
 *       - Generate analysisSize fresh PN values from the streaming PRNG
 *         (consuming the same count the embedder did for that frame).
 *       - FFT the PN.
 *       - Compute spectral correlation between audio and PN spectra.
 *    c. Average the per-frame spectral correlations.
 * 4. The user with the highest average correlation is the best candidate.
 *
 * PN generation exactly mirrors JS generatePN() / the worklet constructor:
 * - ALL string seeds (numeric-looking or not) → djb2 hashString() → mulberry32.
 *   The JS worklet has no "numeric string -> raw integer" branch; it always
 *   hashes string seeds. The detector must do the same or it will derive a
 *   completely different PRNG stream for any user whose seed happens to be
 *   a numeric string, causing that user's true correlation to collapse to
 *   noise and a different (wrong) user to be reported as the match.
 * - Chips: rand() * 2 − 1 (continuous float, not binary ±1)
 *
 * FFT, Hann window, and Bark-band mapping are ported from the JS worklet.
 *
 * FIDELITY NOTE: the embedder does NOT add the shaped PN spectrum to the
 * audio in the frequency domain. For each hop it inverse-FFTs the shaped PN
 * back to the time domain, re-windows it, overlap-adds it into an
 * accumulator across consecutive frames, and only then adds the accumulated
 * time-domain signal to the original audio (with clipping) to produce the
 * output. This detector mirrors that synthesis exactly: for each candidate
 * seed it reconstructs a full-length, watermark-only "predicted" time-domain
 * track via the same masking-threshold shaping -> IFFT -> re-window ->
 * overlap-add pipeline, then computes a normalised time-domain cross
 * correlation between that reconstruction and the actual recorded audio.
 * Because the PN sequence is noise-like, only the correct seed's
 * reconstruction lines up with what's actually embedded in the recording;
 * an unrelated seed's reconstruction is close to uncorrelated with it, and
 * the underlying speech is uncorrelated with either.
 *
 * WARM-UP NOTE: the embedder's analysis buffer starts zero-filled
 * (this._analysisBuf = new Float32Array(analysisSize)) and slides left by
 * one hop per frame, so the very first frame or two analyse a window that
 * is partly zeros. The detector reproduces that exact sliding/zero-padded
 * buffer (see `analysisBuf` below) instead of reading raw
 * samples[off..off+analysisSize-1] directly, which previously desynchronised
 * the streaming PRNG from the audio content by roughly one frame and
 * collapsed correlation to noise.
 */
@Service
public class WatermarkDetectionService {

    /**
     * Minimum whole-recording normalised correlation to declare detection.
     *
     * IMPORTANT — CALIBRATION: this score is a whole-recording time-domain
     * cross-correlation (see computeWatermarkCorrelation), which for this
     * watermarking scheme tops out well below 1.0 even for a correct match —
     * the watermark is deliberately shaped to sit at/under the psychoacoustic
     * masking threshold, so its energy relative to the underlying speech is
     * small by design. Observed real correlations for a correct match on
     * this pipeline have been in the ~0.02–0.03 range, with incorrect
     * candidates scoring negative or near-zero. A threshold of 0.15 (an
     * earlier placeholder) was far too high and caused true positives to be
     * reported as "not detected".
     *
     * 0.015 below is a reasonable starting point given observed data, but
     * you should still calibrate this against a larger set of known-good /
     * known-bad recordings for your specific alpha/frameSize/analysisWindow
     * settings, since the achievable correlation scales with the watermark
     * strength (alpha) you embed with.
     */
    private static final double DETECTION_THRESHOLD = 0.015;

    /**
     * Minimum gap between the best and second-best score required to trust
     * the winner. Without this, two close/noisy scores can flip the
     * "detected" user essentially at random. Lowered alongside
     * DETECTION_THRESHOLD to match the real achievable correlation scale —
     * an earlier value of 0.05 was larger than most real winning margins on
     * this pipeline (e.g. an observed correct-match margin of ~0.044 would
     * have only barely cleared it, and smaller-but-still-real margins would
     * not have). Tune this together with DETECTION_THRESHOLD against real
     * data.
     */
    private static final double MIN_SCORE_MARGIN = 0.01;

    @Autowired
    private WatermarkConfigRepository repository;

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

        // Number of frames — one per hop, matching the embedder which processes
        // one hop of new audio per frame (with a sliding analysis window).
        int hop = frameSize;
        int numFrames = samples.length / hop;

        if (numFrames == 0) {
            return new WatermarkDetectionResponse(
                    null, null, sessionId, 0.0, false, 0, sessionConfigs.size(),
                    Collections.emptyMap(),
                    Collections.emptyMap(),
                    "Audio too short for detection (need at least " + hop + " samples).");
        }

        // ── 3. Score each user using spectral correlation ───────────────────
        Map<String, Double> allUserScores = new LinkedHashMap<>();
        Map<String, String> userDisplayNames = new LinkedHashMap<>();

        for (WatermarkConfigRepository.DetectionConfigProjection config : sessionConfigs) {
            double score = computeWatermarkCorrelation(
                    samples, config.getSeed(), hop, analysisSize, numBands,
                    sampleRate, window, binToBand, config.getAlpha());
            allUserScores.put(config.getUserId(), round4(score));
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
            // Only one candidate existed - no margin to compare against.
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

        return new WatermarkDetectionResponse(
                detected ? winnerConfig.getUserId() : null,
                detected ? winnerConfig.getDisplayName() : null,
                sessionId,
                round4(bestScore),
                detected,
                numFrames,
                sessionConfigs.size(),
                allUserScores,
                userDisplayNames,
                message);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Watermark reconstruction + correlation — mirrors the embedder pipeline
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Reconstruct the full-length, watermark-only time-domain signal that the
     * given seed would have produced (via the same masking-threshold shaping,
     * inverse FFT, re-windowing, and overlap-add the embedder uses), then
     * return the normalised time-domain cross-correlation between that
     * reconstruction and the actual recorded audio.
     *
     * The masking threshold for each frame is still derived from the actual
     * recorded audio's spectrum (same as the embedder would have derived it
     * from the original audio at embed time — this is a reasonable
     * approximation since the watermark's own energy is small relative to the
     * underlying speech/audio it's masked against). What changes vs. a naive
     * frequency-domain comparison is that the shaped PN is carried all the way
     * through IFFT -> re-window -> overlap-add into a real time-domain
     * reconstruction, exactly mirroring what the embedder actually wrote into
     * the recording, before scoring.
     */
    private double computeWatermarkCorrelation(
            float[] samples, String seed, int hop, int analysisSize,
            int numBands, float sampleRate, float[] window, int[] binToBand, double alpha) {

        // Create a streaming PRNG — same initial state as the embedder
        long[] prngState = { resolveSeed(seed) };

        // The embedder's margin in linear amplitude
        double marginLinear = Math.pow(10.0, alpha / 20.0);

        // Scratch buffers
        double[] audioRe = new double[analysisSize];
        double[] audioIm = new double[analysisSize];
        double[] pnRe = new double[analysisSize];
        double[] pnIm = new double[analysisSize];
        double[] bandEnergy = new double[numBands];
        double[] bandLogSum = new double[numBands];
        double[] bandCount = new double[numBands];
        double[] bandFlatness = new double[numBands];
        double[] bandThreshold = new double[numBands];
        double[] spreadThreshold = new double[numBands];

        // Full-length reconstruction buffer: the predicted watermark-only
        // signal this seed would have produced, built via overlap-add across
        // all frames (mirrors this._olaAcc accumulation in the JS worklet,
        // but computed offline over the whole recording at once).
        double[] recon = new double[samples.length];

        // Sliding analysis buffer — starts zero-filled to mirror the embedder's
        // constructor: this._analysisBuf = new Float32Array(analysisSize).
        // The embedder slides this buffer left by one hop per frame and fills
        // the tail with new samples, so the very first frame analyses a window
        // that is mostly zeros. Reading raw samples[off..off+analysisSize-1]
        // directly (an earlier approach) desynchronised the streaming PRNG
        // from the audio content by roughly one frame and collapsed the
        // correlation to noise.
        double[] analysisBuf = new double[analysisSize];

        int numDetectorFrames = samples.length / hop;
        for (int f = 0; f < numDetectorFrames; f++) {
            int off = f * hop;

            // Slide analysis buffer left by hop, insert new samples at end
            // (mirrors: this._analysisBuf.copyWithin(0, hop);
            //          this._analysisBuf.set(samples, N - hop);)
            System.arraycopy(analysisBuf, hop, analysisBuf, 0, analysisSize - hop);
            int copyCount = Math.min(hop, samples.length - off);
            for (int i = 0; i < copyCount; i++) {
                analysisBuf[analysisSize - hop + i] = samples[off + i];
            }
            for (int i = copyCount; i < hop; i++) {
                analysisBuf[analysisSize - hop + i] = 0.0;
            }

            // ── 1. Window the analysis buffer (for masking-threshold analysis)
            for (int i = 0; i < analysisSize; i++) {
                audioRe[i] = analysisBuf[i] * window[i];
                audioIm[i] = 0.0;
            }
            fft(audioRe, audioIm, false);

            // ── 2. Bark-band energy + spectral flatness ─────────────────────
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

            // ── 3. Masking threshold per band ───────────────────────────────
            for (int b = 0; b < numBands; b++) {
                double flat = Math.min(Math.max(bandFlatness[b], 0.0), 1.0);
                double offsetDb = 18.0 - flat * 12.0;
                double energyDb = 10.0 * Math.log10(bandEnergy[b] + 1e-12);
                bandThreshold[b] = Math.pow(10.0, (energyDb - offsetDb) / 10.0);
            }
            // 3-tap spreading — mirrors the JS worklet's IN-PLACE cascading
            // update exactly: JS overwrites this._bandThreshold[b] before
            // reading it as "left" for band b+1, so this is a left-to-right
            // cascading filter, not a symmetric spread from the original
            // (unmodified) array. Reproduce that same order here.
            for (int b = 0; b < numBands; b++) {
                double left = b > 0 ? spreadThreshold[b - 1] : bandThreshold[b];
                double right = b < numBands - 1 ? bandThreshold[b + 1] : bandThreshold[b];
                spreadThreshold[b] = 0.5 * bandThreshold[b] + 0.25 * left + 0.25 * right;
            }

            // ── 4. Generate this frame's PN spectrum (streaming PRNG) ───────
            for (int k = 0; k < analysisSize; k++) {
                pnRe[k] = mulberry32Next(prngState) * 2.0 - 1.0;
                pnIm[k] = 0.0;
            }
            fft(pnRe, pnIm, false);

            // ── 5. Shape PN spectrum by sqrt(threshold) * margin ────────────
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

            // ── 6. Inverse FFT back to time domain, then re-window ──────────
            // Mirrors: _fft(pnRe, pnIm, true) followed by pnRe[i] *= window[i]
            fft(pnRe, pnIm, true);
            for (int i = 0; i < analysisSize; i++) {
                pnRe[i] *= window[i];
            }

            // ── 7. Overlap-add this frame's time-domain contribution into
            //      the full-length reconstruction, positioned at this frame's
            //      offset — mirrors this._olaAcc[i] += pnRe[i] in the worklet,
            //      unrolled across the whole recording instead of a rolling
            //      hop-sized buffer.
            for (int i = 0; i < analysisSize && (off + i) < recon.length; i++) {
                recon[off + i] += pnRe[i];
            }
        }

        // ── 8. Normalised time-domain cross-correlation between the
        //      reconstructed watermark-only track and the actual recording.
        double corr = 0.0;
        double reconPower = 0.0;
        double audioPower = 0.0;
        for (int i = 0; i < samples.length; i++) {
            double a = samples[i];
            double r = recon[i];
            corr += a * r;
            reconPower += r * r;
            audioPower += a * a;
        }
        double denom = Math.sqrt(reconPower * audioPower);
        return denom > 1e-12 ? corr / denom : 0.0;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // FFT — exact port of the JS worklet's radix-2 iterative FFT
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * In-place iterative radix-2 FFT (or inverse FFT when invert=true).
     * Mirrors the JS _fft(re, im, invert) in audio-processor.worklet.js.
     */
    private static void fft(double[] re, double[] im, boolean invert) {
        int n = re.length;
        // Bit-reversal permutation
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
        // Butterfly passes
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
    // Audio decoding — reads sample rate from the WAV header
    // ─────────────────────────────────────────────────────────────────────────

    /** Holds decoded audio samples and the source sample rate. */
    private static class DecodedAudio {
        final float[] samples;
        final float sampleRate;
        DecodedAudio(float[] samples, float sampleRate) {
            this.samples = samples;
            this.sampleRate = sampleRate;
        }
    }

    /**
     * Decode the uploaded audio file to normalised float32 samples in [-1, +1].
     * Also extracts the sample rate from the audio header.
     */
    private DecodedAudio decodeAudioToFloatSamples(MultipartFile audioFile)
            throws IOException, UnsupportedAudioFileException {

        byte[] fileBytes = audioFile.getBytes();

        try (AudioInputStream ais = AudioSystem.getAudioInputStream(
                new BufferedInputStream(new ByteArrayInputStream(fileBytes)))) {

            AudioFormat src = ais.getFormat();
            float sampleRate = src.getSampleRate();

            // Target: 16-bit signed mono little-endian PCM
            AudioFormat pcmFormat = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    sampleRate,
                    16,
                    1,       // mono
                    2,       // 2 bytes per frame
                    sampleRate,
                    false);  // little-endian

            try (AudioInputStream pcm = AudioSystem.getAudioInputStream(pcmFormat, ais)) {
                return new DecodedAudio(pcmBytesToFloats(pcm.readAllBytes()), sampleRate);
            }

        } catch (UnsupportedAudioFileException e) {
            // Fallback: assume raw 16-bit PCM at 48000 Hz
            return new DecodedAudio(pcmBytesToFloats(fileBytes), 48000f);
        }
    }

    /**
     * Convert raw 16-bit little-endian signed PCM bytes to float32 in [-1, +1].
     */
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
     * worklet does. IMPORTANT: the JS worklet (_createMulberry32 usage in
     * the constructor and _generatePN) ALWAYS hashes string seeds via
     * _hashString — there is no "numeric string -> parse as raw integer"
     * branch on the JS side. A seed is only used as a raw integer when it is
     * an actual JS number (seed >>> 0), never when it's a string, even if
     * that string looks numeric (e.g. "1001").
     *
     * An earlier version of this method special-cased numeric-looking
     * strings and used them as raw integers, which diverges from the
     * embedder's PRNG stream for any user whose stored seed happens to be a
     * numeric string, causing that user's watermark to go undetected and a
     * different user to be reported as the match instead. Since seeds
     * arriving here are always strings (from the DB / repository), they must
     * always be hashed.
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

    /**
     * One step of mulberry32 — exact port of JS mulberry32().
     * All values kept as unsigned 32-bit via masking with 0xFFFFFFFFL.
     */
    private double mulberry32Next(long[] state) {
        state[0] = (state[0] + 0x6d2b79f5L) & 0xFFFFFFFFL;
        long s = state[0];
        long t = imul32(s ^ (s >>> 15), 1L | s);
        t = (t + imul32(t ^ (t >>> 7), 61L | t)) ^ t;
        t = (t ^ (t >>> 14)) & 0xFFFFFFFFL;
        return t / 4294967296.0;
    }

    /**
     * Unsigned 32-bit multiply — mirrors JS Math.imul().
     */
    private long imul32(long a, long b) {
        return (a & 0xFFFFFFFFL) * (b & 0xFFFFFFFFL) & 0xFFFFFFFFL;
    }

    private double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}