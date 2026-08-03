package com.convo.audio_watermark.service;

import com.convo.audio_watermark.repository.WatermarkConfigRepository;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Cycle-bounded synchronization search.
 *
 * SYNCHRONIZATION (repeating-tag design): the embedder no longer runs one
 * never-repeating PRNG stream for the whole call. Instead, its watermark
 * repeats on a fixed cycle (see cycleSeconds / hopsPerCycle) — frame f's
 * pattern depends only on (f mod hopsPerCycle), never on how long the call
 * has been running. That means ANY recording at least one cycle long is
 * guaranteed to contain a full repetition somewhere in it, and this
 * search only ever needs to search within ONE cycle's length — a fixed,
 * small window — rather than across the whole call, which is what made the
 * earlier frame-0-alignment / whole-call-search approaches either fragile
 * (needed a cooperating "reset" signal) or expensive (search cost grew with
 * meeting length).
 *
 * The search is still two-dimensional, but the two dimensions behave very
 * differently and are searched differently:
 * 1. cyclePos — which position within the repeat cycle the recording's
 *    first frame corresponds to. Bounded to [0, hopsPerCycle), which is
 *    small and FIXED regardless of call length (e.g. ~3750 positions for a
 *    20-second cycle at a 256-sample hop / 48kHz) — but must be scanned
 *    EXHAUSTIVELY (see stage 1 below): each step jumps the PRNG by a full
 *    analysisSize draws, so neighboring cyclePos values are statistically
 *    independent of each other, with no partial correlation to guide a
 *    coarse-then-refine search toward the true one.
 * 2. phase — the exact sample offset within a hop the recording begins at.
 *    Unlike cyclePos, neighboring phases share most of the same underlying
 *    audio samples in their analysis window, so the score varies smoothly
 *    here — a coarse grid + local refine is valid for this dimension.
 *
 * Jumping the PRNG to a candidate cyclePos is O(1) (see
 * WatermarkDsp.stateAfterDraws) rather than stepping through every
 * preceding frame, and the audio-side masking-threshold analysis for a
 * given phase is shared across every user and every cyclePos candidate
 * tested at that phase (see WatermarkDsp.analyzeAudioFrames) since it
 * depends only on the recorded samples.
 *
 * SCHEMA NOTE: this assumes each user's registered config also carries a
 * cycleSeconds value matching what the embedder used (see
 * DetectionConfigProjection.getCycleSeconds()) — add this alongside
 * frameSize / analysisWindowSize / numBands if it isn't already present in
 * your config table/projection/DTO plumbing.
 */
@Component
class WatermarkSearchEngine {

    /**
     * Total coarse sample-phase candidates spread across one hop.
     *
     * CALIBRATED FROM MEASUREMENT, not a guess: probing the actual score-vs-
     * phase curve at a known-correct cyclePos (holding cyclePos fixed, then
     * scoring every one of the 256 possible phase values at a 256-sample
     * hop) showed a real, exploitable peak at the true phase, but one that
     * decays to noise-floor within roughly +/-10 samples — i.e. much
     * narrower than assumed. The previous value of 8 gave a 32-sample
     * stride (worst case 16 samples from the true phase — already past the
     * peak, into the noise floor), which combined with the cyclePos issue
     * documented above made the coarse stage reliably miss real alignments.
     * 32 candidates gives an 8-sample stride (worst case 4 samples off —
     * still solidly on the peak).
     */
    private static final int PHASE_COARSE_CANDIDATES = 32;

    /**
     * Frames used during the coarse + refine search stages (~0.85s of audio
     * at a 256-sample hop / 48kHz). The final reported score always uses the
     * FULL recording at the winning alignment (see stage 3 below), so this
     * only affects how reliably the search LOCATES the right alignment, not
     * the quality of the final reported score — but "how reliably" is not a
     * minor concern: at 80, empirically, the exhaustive fallback search
     * (see stage 3b in WatermarkDetectionService.detect()) missed the true
     * alignment outright on a reproducible ~1-in-6 to ~1-in-3 basis
     * (verified against known synthetic recordings; more candidates tested
     * per request raises the noise-floor "best wrong score" via order
     * statistics, and 80 frames wasn't enough averaging per candidate to
     * keep the true one clear of it). 160 was verified against a
     * known-failing case (scored 0.0145, under DETECTION_THRESHOLD, at 80)
     * and fully recovered it (0.288, matching the oracle ceiling). This
     * raises search cost roughly proportionally, which is an acceptable
     * trade now that the exhaustive search is only the FALLBACK path (see
     * the near-cyclePos=0 fast path in detect()) rather than the common
     * case.
     */
    private static final int SEARCH_FRAME_COUNT = 160;

    /**
     * Dedicated pool for parallelizing per-user candidate scoring in
     * findBestScoresAcrossUsers. Each registered user's search (Stage 1/2/3)
     * is independent of every other user's, so with N users and C cores this
     * cuts wall-clock time roughly from O(N) sequential work to O(N / C).
     *
     * Deliberately a private pool rather than parallelStream()'s shared
     * ForkJoinPool.commonPool() — that pool is also used by unrelated
     * parallel streams and reactive/async code elsewhere in the app, so
     * sharing it would let unrelated work stall detection requests (and
     * vice versa) under load.
     *
     * CAUTION: on some container/hosting platforms (e.g. small/free-tier
     * instances with a fractional CPU quota) the JVM's availableProcessors()
     * reports the host machine's visible core count rather than the tiny CPU
     * share actually granted — sizing the pool from it can spin up far more
     * concurrent threads than the CPU can service, adding scheduling
     * contention on top of an already slow request instead of helping.
     */
    private final int numWorkers = Runtime.getRuntime().availableProcessors();

    private final ExecutorService userScoringExecutor =
            Executors.newFixedThreadPool(numWorkers);

    @PreDestroy
    void shutdownExecutor() {
        userScoringExecutor.shutdown();
    }

    /** Tracks the best (phase, cyclePos, score) found so far for one user. */
    private static class BestAlignment {
        double score = Double.NEGATIVE_INFINITY;
        int phase = 0;
        long cyclePos = 0;
    }

    /**
     * Scratch buffers reused across every candidate evaluated by ONE
     * parallel task, instead of each candidate allocating (and
     * zero-initializing) its own. With thousands of candidates tested per
     * request, fresh allocation per call was the original bottleneck — not
     * the actual FFT/correlation work — since {@code recon} in particular
     * can be tens of thousands of elements.
     *
     * ONE INSTANCE PER TASK, NOT PER USER: search work is chunked across
     * candidates (see findBestScoresAcrossUsers), not just across users —
     * a single user's search can now run as several concurrent chunks on
     * different threads, so buffers must be scoped to whichever chunk owns
     * them, not shared per-user (that would let concurrent chunks corrupt
     * each other's scratch arrays mid-FFT). The number of tasks is still
     * small and bounded (phases × users × workers), so this stays far
     * cheaper than the original per-candidate allocation.
     */
    private static class ScratchBuffers {
        final double[] pnRe;
        final double[] pnIm;
        final double[] recon;
        final long[] prngState = new long[1]; // reused instead of allocated per frame

        ScratchBuffers(int analysisSize, int maxReconLen) {
            pnRe = new double[analysisSize];
            pnIm = new double[analysisSize];
            recon = new double[maxReconLen];
        }
    }

    /** Thread-safe "keep if better" update — multiple chunks for the same user can finish concurrently. */
    private static void updateBestAlignment(BestAlignment ba, double score, int phase, long cyclePos) {
        synchronized (ba) {
            if (score > ba.score) {
                ba.score = score;
                ba.phase = phase;
                ba.cyclePos = cyclePos;
            }
        }
    }

    /**
     * @param cyclePosLimit if non-null, only cyclePos candidates within
     *                      {@code cyclePosLimit} hops of 0 are tried, in
     *                      EITHER direction — [0, limit) AND
     *                      (hopsPerCycle - limit, hopsPerCycle) — used for
     *                      the cheap near-zero fast pass. Null means the
     *                      full exhaustive [0, hopsPerCycle) range, as
     *                      required for external/uncooperative recordings
     *                      with no known alignment.
     *
     *                      The backward side matters: a real recording
     *                      pinned by a "reset-prng" signal doesn't
     *                      necessarily land at or after cyclePos=0 — the
     *                      audio actually captured can correspond to
     *                      samples the embedder generated slightly BEFORE
     *                      the reset took effect (e.g. buffering in a
     *                      cross-AudioContext MediaStream bridge), which
     *                      wraps to just UNDER hopsPerCycle, not just over
     *                      0. Confirmed against a real recording where the
     *                      reset-prng message itself was delivered
     *                      promptly (~7ms) but the true alignment was
     *                      still 6 hops shy of hopsPerCycle — a
     *                      forward-only window scored that case as pure
     *                      noise and let another user's noise ceiling win.
     */
    Map<String, Double> findBestScoresAcrossUsers(
            float[] samples,
            List<WatermarkConfigRepository.DetectionConfigProjection> sessionConfigs,
            int hop, int analysisSize, int numBands, float sampleRate,
            float[] window, int[] binToBand, Long cyclePosLimit) {

        int numDetectorFrames = samples.length / hop;
        int searchFrameCount = Math.min(SEARCH_FRAME_COUNT, numDetectorFrames);

        int phaseStride = Math.max(1, hop / PHASE_COARSE_CANDIDATES);
        int maxReconLen = numDetectorFrames * hop + analysisSize;

        Map<String, BestAlignment> bestByUser = new ConcurrentHashMap<>();
        Map<String, Long> hopsPerCycleByUser = new HashMap<>();
        for (WatermarkConfigRepository.DetectionConfigProjection c : sessionConfigs) {
            bestByUser.put(c.getUserId(), new BestAlignment());
            // Each user's cycle length in hops — MUST match what their
            // embedder used (config.cycleSeconds), not a shared constant,
            // in case that's ever configured per-user.
            long hopsPerCycle = Math.max(1, Math.round((c.getCycleSeconds() * sampleRate) / hop));
            hopsPerCycleByUser.put(c.getUserId(), hopsPerCycle);
        }

        // ── Stage 1: coarse phase scan × EXHAUSTIVE cyclePos scan. The
        //    audio-side masking analysis for a given phase is
        //    shared/sequential (cheap, one FFT pass over ~80 frames); for
        //    each user at that phase, the cyclePos range is split into
        //    numWorkers chunks and every chunk — across every user — is
        //    submitted as its own independent task, so parallelism scales
        //    with core count, not with how many people were in the call.
        //
        //    cyclePos MUST be scanned exhaustively (stride 1) — see the
        //    class doc for why a subsampled grid can't work here. This
        //    used to be subsampled to 1-in-~(hopsPerCycle/200) candidates,
        //    which meant real alignment was found only by chance —
        //    verified against known synthetic recordings where the true
        //    (phase, cyclePos) was known: the subsampled search
        //    consistently missed it, reporting scores near the noise floor
        //    despite the true alignment scoring 5-40x higher.
        for (int phase = 0; phase < hop; phase += phaseStride) {
            int framesAvailable = Math.min(searchFrameCount, (samples.length - phase) / hop);
            if (framesAvailable <= 0) continue;

            WatermarkDsp.FrameAnalysis analysis = WatermarkDsp.analyzeAudioFrames(
                    samples, phase, hop, analysisSize, numBands, window, binToBand, framesAvailable);

            final int phaseF = phase;
            final int framesAvailableF = framesAvailable;
            List<CompletableFuture<Void>> futures = new ArrayList<>();

            for (WatermarkConfigRepository.DetectionConfigProjection c : sessionConfigs) {
                long hopsPerCycle = hopsPerCycleByUser.get(c.getUserId());
                // wrapLimit > 0 means the candidate set is two disjoint
                // windows — [0, wrapLimit) and (hopsPerCycle - wrapLimit,
                // hopsPerCycle) — rather than one contiguous range. Once the
                // requested limit covers at least half the cycle the two
                // windows would overlap/duplicate, so fall back to a single
                // exhaustive [0, hopsPerCycle) range instead.
                long wrapLimit = 0;
                long totalCandidates;
                if (cyclePosLimit != null && 2 * Math.min(cyclePosLimit, hopsPerCycle) < hopsPerCycle) {
                    wrapLimit = Math.min(cyclePosLimit, hopsPerCycle);
                    totalCandidates = 2 * wrapLimit;
                } else {
                    totalCandidates = hopsPerCycle;
                }
                final long wrapLimitF = wrapLimit;
                int chunks = (int) Math.min(numWorkers, Math.max(1, totalCandidates));
                long chunkSize = (totalCandidates + chunks - 1) / chunks;
                BestAlignment ba = bestByUser.get(c.getUserId());

                for (int ci = 0; ci < chunks; ci++) {
                    long startIdx = (long) ci * chunkSize;
                    long endIdxExclusive = Math.min(totalCandidates, startIdx + chunkSize);
                    if (startIdx >= endIdxExclusive) continue;

                    futures.add(CompletableFuture.runAsync(() -> {
                        ScratchBuffers buffers = new ScratchBuffers(analysisSize, maxReconLen);
                        double localBestScore = Double.NEGATIVE_INFINITY;
                        long localBestCyclePos = 0;

                        for (long idx = startIdx; idx < endIdxExclusive; idx++) {
                            // idx < wrapLimitF -> forward window [0, wrapLimitF).
                            // Otherwise -> backward/wraparound window
                            // (hopsPerCycle - wrapLimitF, hopsPerCycle).
                            // wrapLimitF == 0 means no split: idx IS the cyclePos.
                            long cyclePos = (wrapLimitF == 0 || idx < wrapLimitF)
                                    ? idx
                                    : hopsPerCycle - wrapLimitF + (idx - wrapLimitF);
                            double score = scoreCandidate(
                                    samples, phaseF, analysis, c, cyclePos, hopsPerCycle, hop, analysisSize,
                                    numBands, window, binToBand, framesAvailableF, buffers);
                            if (score > localBestScore) {
                                localBestScore = score;
                                localBestCyclePos = cyclePos;
                            }
                        }
                        updateBestAlignment(ba, localBestScore, phaseF, localBestCyclePos);
                    }, userScoringExecutor));
                }
            }
            futures.forEach(future -> future.join());
        }

        // ── Stage 2: local refine at full (1-sample, 1-hop) resolution
        //    around each user's best coarse candidate. The (phase, cyclePos)
        //    refine grid is flattened and chunked the same way as stage 1.
        //    cyclePos only needs a +/-1 hedge here (not a wide window): the
        //    coarse stage above already scanned every cyclePos exhaustively
        //    at its chosen phase, so this is only covering the case where a
        //    refined (in-between) phase shifts the ideal cyclePos by one.
        List<CompletableFuture<Void>> refineFutures = new ArrayList<>();
        for (WatermarkConfigRepository.DetectionConfigProjection c : sessionConfigs) {
            BestAlignment ba = bestByUser.get(c.getUserId());
            long hopsPerCycle = hopsPerCycleByUser.get(c.getUserId());
            long cycleStride = 1;

            int phaseLo = Math.max(0, ba.phase - phaseStride);
            int phaseHi = Math.min(hop - 1, ba.phase + phaseStride);
            long cycleLo = Math.max(0, ba.cyclePos - cycleStride);
            long cycleHi = Math.min(hopsPerCycle - 1, ba.cyclePos + cycleStride);

            int numPhases = phaseHi - phaseLo + 1;
            long numCycles = cycleHi - cycleLo + 1;
            long totalCells = (long) numPhases * numCycles;
            int chunks = (int) Math.min(numWorkers, Math.max(1, totalCells));
            long cellChunkSize = (totalCells + chunks - 1) / chunks;

            for (int ci = 0; ci < chunks; ci++) {
                long startCell = (long) ci * cellChunkSize;
                long endCellExclusive = Math.min(totalCells, startCell + cellChunkSize);
                if (startCell >= endCellExclusive) continue;

                refineFutures.add(CompletableFuture.runAsync(() -> {
                    ScratchBuffers buffers = new ScratchBuffers(analysisSize, maxReconLen);
                    double localBestScore = Double.NEGATIVE_INFINITY;
                    int localBestPhase = phaseLo;
                    long localBestCyclePos = cycleLo;
                    // Cache analysis per phase within this chunk, since
                    // consecutive cells often share the same phase and
                    // recomputing it per cyclePos would be wasteful.
                    int cachedPhase = Integer.MIN_VALUE;
                    WatermarkDsp.FrameAnalysis cachedAnalysis = null;

                    for (long cell = startCell; cell < endCellExclusive; cell++) {
                        int phase = phaseLo + (int) (cell / numCycles);
                        long cyclePos = cycleLo + (cell % numCycles);

                        int framesAvailable = Math.min(searchFrameCount, (samples.length - phase) / hop);
                        if (framesAvailable <= 0) continue;

                        if (phase != cachedPhase) {
                            cachedAnalysis = WatermarkDsp.analyzeAudioFrames(
                                    samples, phase, hop, analysisSize, numBands, window, binToBand, framesAvailable);
                            cachedPhase = phase;
                        }

                        double score = scoreCandidate(
                                samples, phase, cachedAnalysis, c, cyclePos, hopsPerCycle, hop, analysisSize,
                                numBands, window, binToBand, framesAvailable, buffers);
                        if (score > localBestScore) {
                            localBestScore = score;
                            localBestPhase = phase;
                            localBestCyclePos = cyclePos;
                        }
                    }
                    updateBestAlignment(ba, localBestScore, localBestPhase, localBestCyclePos);
                }, userScoringExecutor));
            }
        }
        refineFutures.forEach(future -> future.join());

        // ── Stage 3: final score at the pinpointed alignment, over the
        //    WHOLE recording — this is what's actually compared against
        //    the detection threshold in WatermarkDetectionService. One task
        //    per user is fine here — it's a single evaluation each, not a
        //    search.
        Map<String, Double> finalScores = new ConcurrentHashMap<>();
        List<CompletableFuture<Void>> finalFutures = new ArrayList<>(sessionConfigs.size());
        for (WatermarkConfigRepository.DetectionConfigProjection c : sessionConfigs) {
            finalFutures.add(CompletableFuture.runAsync(() -> {
                BestAlignment ba = bestByUser.get(c.getUserId());
                int framesAvailable = (samples.length - ba.phase) / hop;
                if (framesAvailable <= 0) {
                    finalScores.put(c.getUserId(), 0.0);
                    return;
                }

                ScratchBuffers buffers = new ScratchBuffers(analysisSize, maxReconLen);
                WatermarkDsp.FrameAnalysis analysis = WatermarkDsp.analyzeAudioFrames(
                        samples, ba.phase, hop, analysisSize, numBands, window, binToBand, framesAvailable);
                long hopsPerCycle = hopsPerCycleByUser.get(c.getUserId());
                double score = scoreCandidate(
                        samples, ba.phase, analysis, c, ba.cyclePos, hopsPerCycle, hop, analysisSize,
                        numBands, window, binToBand, framesAvailable, buffers);

                finalScores.put(c.getUserId(), score);
            }, userScoringExecutor));
        }
        finalFutures.forEach(future -> future.join());

        // Preserve original registration order in the returned map (used
        // for display ordering downstream), since ConcurrentHashMap doesn't.
        Map<String, Double> ordered = new LinkedHashMap<>();
        for (WatermarkConfigRepository.DetectionConfigProjection c : sessionConfigs) {
            ordered.put(c.getUserId(), finalScores.get(c.getUserId()));
        }
        return ordered;
    }

    private double scoreCandidate(
            float[] samples, int phase, WatermarkDsp.FrameAnalysis analysis,
            WatermarkConfigRepository.DetectionConfigProjection config, long cyclePos, long hopsPerCycle,
            int hop, int analysisSize, int numBands, float[] window, int[] binToBand,
            int frameCount, ScratchBuffers buffers) {

        long baseSeedState = WatermarkDsp.resolveSeed(config.getSeed());
        double marginLinear = Math.pow(10.0, config.getAlpha() / 20.0);
        return WatermarkDsp.scoreWithAnalysis(
                samples, phase, analysis, baseSeedState, cyclePos, hopsPerCycle, hop, analysisSize,
                numBands, window, binToBand, marginLinear, frameCount,
                buffers.pnRe, buffers.pnIm, buffers.recon, buffers.prngState);
    }
}
