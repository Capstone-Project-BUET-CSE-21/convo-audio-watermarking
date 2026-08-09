// package com.convo.audio_watermark.service;

// import com.convo.audio_watermark.repository.WatermarkConfigRepository;
// import jakarta.annotation.PreDestroy;
// import org.springframework.stereotype.Component;

// import java.util.ArrayList;
// import java.util.HashMap;
// import java.util.LinkedHashMap;
// import java.util.List;
// import java.util.Map;
// import java.util.concurrent.CompletableFuture;
// import java.util.concurrent.ConcurrentHashMap;
// import java.util.concurrent.ExecutorService;
// import java.util.concurrent.Executors;

// /**
//  * Cycle-bounded synchronization search.
//  *
//  * SYNCHRONIZATION (repeating-tag design): the embedder no longer runs one
//  * never-repeating PRNG stream for the whole call. Instead, its watermark
//  * repeats on a fixed cycle (see cycleSeconds / hopsPerCycle) — frame f's
//  * pattern depends only on (f mod hopsPerCycle), never on how long the call
//  * has been running. That means ANY recording at least one cycle long is
//  * guaranteed to contain a full repetition somewhere in it, and this
//  * search only ever needs to search within ONE cycle's length — a fixed,
//  * small window — rather than across the whole call, which is what made the
//  * earlier frame-0-alignment / whole-call-search approaches either fragile
//  * (needed a cooperating "reset" signal) or expensive (search cost grew with
//  * meeting length).
//  *
//  * The search is still two-dimensional, but the two dimensions behave very
//  * differently and are searched differently:
//  * 1. cyclePos — which position within the repeat cycle the recording's
//  *    first frame corresponds to. Bounded to [0, hopsPerCycle), which is
//  *    small and FIXED regardless of call length (e.g. ~3750 positions for a
//  *    20-second cycle at a 256-sample hop / 48kHz) — but must be scanned
//  *    EXHAUSTIVELY (see stage 1 below): each step jumps the PRNG by a full
//  *    analysisSize draws, so neighboring cyclePos values are statistically
//  *    independent of each other, with no partial correlation to guide a
//  *    coarse-then-refine search toward the true one.
//  * 2. phase — the exact sample offset within a hop the recording begins at.
//  *    Unlike cyclePos, neighboring phases share most of the same underlying
//  *    audio samples in their analysis window, so the score varies smoothly
//  *    here — a coarse grid + local refine is valid for this dimension.
//  *
//  * Jumping the PRNG to a candidate cyclePos is O(1) (see
//  * WatermarkDsp.stateAfterDraws) rather than stepping through every
//  * preceding frame, and the audio-side masking-threshold analysis for a
//  * given phase is shared across every user and every cyclePos candidate
//  * tested at that phase (see WatermarkDsp.analyzeAudioFrames) since it
//  * depends only on the recorded samples.
//  *
//  * SCHEMA NOTE: this assumes each user's registered config also carries a
//  * cycleSeconds value matching what the embedder used (see
//  * DetectionConfigProjection.getCycleSeconds()) — add this alongside
//  * frameSize / analysisWindowSize / numBands if it isn't already present in
//  * your config table/projection/DTO plumbing.
//  */
// @Component
// class WatermarkSearchEngine {

//     /**
//      * Total coarse sample-phase candidates spread across one hop.
//      *
//      * CALIBRATED FROM MEASUREMENT, not a guess: probing the actual score-vs-
//      * phase curve at a known-correct cyclePos (holding cyclePos fixed, then
//      * scoring every one of the 256 possible phase values at a 256-sample
//      * hop) showed a real, exploitable peak at the true phase, but one that
//      * decays to noise-floor within roughly +/-10 samples — i.e. much
//      * narrower than assumed. The previous value of 8 gave a 32-sample
//      * stride (worst case 16 samples from the true phase — already past the
//      * peak, into the noise floor), which combined with the cyclePos issue
//      * documented above made the coarse stage reliably miss real alignments.
//      * 32 candidates gives an 8-sample stride (worst case 4 samples off —
//      * still solidly on the peak).
//      */
//     private static final int PHASE_COARSE_CANDIDATES = 32;

//     /**
//      * Frames used during the coarse + refine search stages (~0.85s of audio
//      * at a 256-sample hop / 48kHz). The final reported score always uses the
//      * FULL recording at the winning alignment (see stage 3 below), so this
//      * only affects how reliably the search LOCATES the right alignment, not
//      * the quality of the final reported score — but "how reliably" is not a
//      * minor concern: at 80, empirically, the exhaustive fallback search
//      * (see stage 3b in WatermarkDetectionService.detect()) missed the true
//      * alignment outright on a reproducible ~1-in-6 to ~1-in-3 basis
//      * (verified against known synthetic recordings; more candidates tested
//      * per request raises the noise-floor "best wrong score" via order
//      * statistics, and 80 frames wasn't enough averaging per candidate to
//      * keep the true one clear of it). 160 was verified against a
//      * known-failing case (scored 0.0145, under DETECTION_THRESHOLD, at 80)
//      * and fully recovered it (0.288, matching the oracle ceiling). This
//      * raises search cost roughly proportionally, which is an acceptable
//      * trade now that the exhaustive search is only the FALLBACK path (see
//      * the near-cyclePos=0 fast path in detect()) rather than the common
//      * case.
//      */
//     private static final int SEARCH_FRAME_COUNT = 160;

//     /**
//      * Dedicated pool for parallelizing per-user candidate scoring in
//      * findBestScoresAcrossUsers. Each registered user's search (Stage 1/2/3)
//      * is independent of every other user's, so with N users and C cores this
//      * cuts wall-clock time roughly from O(N) sequential work to O(N / C).
//      *
//      * Deliberately a private pool rather than parallelStream()'s shared
//      * ForkJoinPool.commonPool() — that pool is also used by unrelated
//      * parallel streams and reactive/async code elsewhere in the app, so
//      * sharing it would let unrelated work stall detection requests (and
//      * vice versa) under load.
//      *
//      * CAUTION: on some container/hosting platforms (e.g. small/free-tier
//      * instances with a fractional CPU quota) the JVM's availableProcessors()
//      * reports the host machine's visible core count rather than the tiny CPU
//      * share actually granted — sizing the pool from it can spin up far more
//      * concurrent threads than the CPU can service, adding scheduling
//      * contention on top of an already slow request instead of helping.
//      */
//     private final int numWorkers = Runtime.getRuntime().availableProcessors();

//     private final ExecutorService userScoringExecutor =
//             Executors.newFixedThreadPool(numWorkers);

//     @PreDestroy
//     void shutdownExecutor() {
//         userScoringExecutor.shutdown();
//     }

//     /** Tracks the best (phase, cyclePos, score) found so far for one user. */
//     private static class BestAlignment {
//         double score = Double.NEGATIVE_INFINITY;
//         int phase = 0;
//         long cyclePos = 0;
//     }

//     /**
//      * Scratch buffers reused across every candidate evaluated by ONE
//      * parallel task, instead of each candidate allocating (and
//      * zero-initializing) its own. With thousands of candidates tested per
//      * request, fresh allocation per call was the original bottleneck — not
//      * the actual FFT/correlation work — since {@code recon} in particular
//      * can be tens of thousands of elements.
//      *
//      * ONE INSTANCE PER TASK, NOT PER USER: search work is chunked across
//      * candidates (see findBestScoresAcrossUsers), not just across users —
//      * a single user's search can now run as several concurrent chunks on
//      * different threads, so buffers must be scoped to whichever chunk owns
//      * them, not shared per-user (that would let concurrent chunks corrupt
//      * each other's scratch arrays mid-FFT). The number of tasks is still
//      * small and bounded (phases × users × workers), so this stays far
//      * cheaper than the original per-candidate allocation.
//      */
//     private static class ScratchBuffers {
//         final double[] pnRe;
//         final double[] pnIm;
//         final double[] recon;
//         final long[] prngState = new long[1]; // reused instead of allocated per frame

//         ScratchBuffers(int analysisSize, int maxReconLen) {
//             pnRe = new double[analysisSize];
//             pnIm = new double[analysisSize];
//             recon = new double[maxReconLen];
//         }
//     }

//     /** Thread-safe "keep if better" update — multiple chunks for the same user can finish concurrently. */
//     private static void updateBestAlignment(BestAlignment ba, double score, int phase, long cyclePos) {
//         synchronized (ba) {
//             if (score > ba.score) {
//                 ba.score = score;
//                 ba.phase = phase;
//                 ba.cyclePos = cyclePos;
//             }
//         }
//     }

//     /**
//      * @param cyclePosLimit if non-null, only cyclePos candidates within
//      *                      {@code cyclePosLimit} hops of 0 are tried, in
//      *                      EITHER direction — [0, limit) AND
//      *                      (hopsPerCycle - limit, hopsPerCycle) — used for
//      *                      the cheap near-zero fast pass. Null means the
//      *                      full exhaustive [0, hopsPerCycle) range, as
//      *                      required for external/uncooperative recordings
//      *                      with no known alignment.
//      *
//      *                      The backward side matters: a real recording
//      *                      pinned by a "reset-prng" signal doesn't
//      *                      necessarily land at or after cyclePos=0 — the
//      *                      audio actually captured can correspond to
//      *                      samples the embedder generated slightly BEFORE
//      *                      the reset took effect (e.g. buffering in a
//      *                      cross-AudioContext MediaStream bridge), which
//      *                      wraps to just UNDER hopsPerCycle, not just over
//      *                      0. Confirmed against a real recording where the
//      *                      reset-prng message itself was delivered
//      *                      promptly (~7ms) but the true alignment was
//      *                      still 6 hops shy of hopsPerCycle — a
//      *                      forward-only window scored that case as pure
//      *                      noise and let another user's noise ceiling win.
//      */
//     Map<String, Double> findBestScoresAcrossUsers(
//             float[] samples,
//             List<WatermarkConfigRepository.DetectionConfigProjection> sessionConfigs,
//             int hop, int analysisSize, int numBands, float sampleRate,
//             float[] window, int[] binToBand, Long cyclePosLimit) {

//         int numDetectorFrames = samples.length / hop;
//         int searchFrameCount = Math.min(SEARCH_FRAME_COUNT, numDetectorFrames);

//         int phaseStride = Math.max(1, hop / PHASE_COARSE_CANDIDATES);
//         int maxReconLen = numDetectorFrames * hop + analysisSize;

//         Map<String, BestAlignment> bestByUser = new ConcurrentHashMap<>();
//         Map<String, Long> hopsPerCycleByUser = new HashMap<>();
//         for (WatermarkConfigRepository.DetectionConfigProjection c : sessionConfigs) {
//             bestByUser.put(c.getUserId(), new BestAlignment());
//             // Each user's cycle length in hops — MUST match what their
//             // embedder used (config.cycleSeconds), not a shared constant,
//             // in case that's ever configured per-user.
//             long hopsPerCycle = Math.max(1, Math.round((c.getCycleSeconds() * sampleRate) / hop));
//             hopsPerCycleByUser.put(c.getUserId(), hopsPerCycle);
//         }

//         // ── Stage 1: coarse phase scan × EXHAUSTIVE cyclePos scan. The
//         //    audio-side masking analysis for a given phase is
//         //    shared/sequential (cheap, one FFT pass over ~80 frames); for
//         //    each user at that phase, the cyclePos range is split into
//         //    numWorkers chunks and every chunk — across every user — is
//         //    submitted as its own independent task, so parallelism scales
//         //    with core count, not with how many people were in the call.
//         //
//         //    cyclePos MUST be scanned exhaustively (stride 1) — see the
//         //    class doc for why a subsampled grid can't work here. This
//         //    used to be subsampled to 1-in-~(hopsPerCycle/200) candidates,
//         //    which meant real alignment was found only by chance —
//         //    verified against known synthetic recordings where the true
//         //    (phase, cyclePos) was known: the subsampled search
//         //    consistently missed it, reporting scores near the noise floor
//         //    despite the true alignment scoring 5-40x higher.
//         for (int phase = 0; phase < hop; phase += phaseStride) {
//             int framesAvailable = Math.min(searchFrameCount, (samples.length - phase) / hop);
//             if (framesAvailable <= 0) continue;

//             WatermarkDsp.FrameAnalysis analysis = WatermarkDsp.analyzeAudioFrames(
//                     samples, phase, hop, analysisSize, numBands, window, binToBand, framesAvailable);

//             final int phaseF = phase;
//             final int framesAvailableF = framesAvailable;
//             List<CompletableFuture<Void>> futures = new ArrayList<>();

//             for (WatermarkConfigRepository.DetectionConfigProjection c : sessionConfigs) {
//                 long hopsPerCycle = hopsPerCycleByUser.get(c.getUserId());
//                 // wrapLimit > 0 means the candidate set is two disjoint
//                 // windows — [0, wrapLimit) and (hopsPerCycle - wrapLimit,
//                 // hopsPerCycle) — rather than one contiguous range. Once the
//                 // requested limit covers at least half the cycle the two
//                 // windows would overlap/duplicate, so fall back to a single
//                 // exhaustive [0, hopsPerCycle) range instead.
//                 long wrapLimit = 0;
//                 long totalCandidates;
//                 if (cyclePosLimit != null && 2 * Math.min(cyclePosLimit, hopsPerCycle) < hopsPerCycle) {
//                     wrapLimit = Math.min(cyclePosLimit, hopsPerCycle);
//                     totalCandidates = 2 * wrapLimit;
//                 } else {
//                     totalCandidates = hopsPerCycle;
//                 }
//                 final long wrapLimitF = wrapLimit;
//                 int chunks = (int) Math.min(numWorkers, Math.max(1, totalCandidates));
//                 long chunkSize = (totalCandidates + chunks - 1) / chunks;
//                 BestAlignment ba = bestByUser.get(c.getUserId());

//                 for (int ci = 0; ci < chunks; ci++) {
//                     long startIdx = (long) ci * chunkSize;
//                     long endIdxExclusive = Math.min(totalCandidates, startIdx + chunkSize);
//                     if (startIdx >= endIdxExclusive) continue;

//                     futures.add(CompletableFuture.runAsync(() -> {
//                         ScratchBuffers buffers = new ScratchBuffers(analysisSize, maxReconLen);
//                         double localBestScore = Double.NEGATIVE_INFINITY;
//                         long localBestCyclePos = 0;

//                         for (long idx = startIdx; idx < endIdxExclusive; idx++) {
//                             // idx < wrapLimitF -> forward window [0, wrapLimitF).
//                             // Otherwise -> backward/wraparound window
//                             // (hopsPerCycle - wrapLimitF, hopsPerCycle).
//                             // wrapLimitF == 0 means no split: idx IS the cyclePos.
//                             long cyclePos = (wrapLimitF == 0 || idx < wrapLimitF)
//                                     ? idx
//                                     : hopsPerCycle - wrapLimitF + (idx - wrapLimitF);
//                             double score = scoreCandidate(
//                                     samples, phaseF, analysis, c, cyclePos, hopsPerCycle, hop, analysisSize,
//                                     numBands, window, binToBand, framesAvailableF, buffers);
//                             if (score > localBestScore) {
//                                 localBestScore = score;
//                                 localBestCyclePos = cyclePos;
//                             }
//                         }
//                         updateBestAlignment(ba, localBestScore, phaseF, localBestCyclePos);
//                     }, userScoringExecutor));
//                 }
//             }
//             futures.forEach(future -> future.join());
//         }

//         // ── Stage 2: local refine at full (1-sample, 1-hop) resolution
//         //    around each user's best coarse candidate. The (phase, cyclePos)
//         //    refine grid is flattened and chunked the same way as stage 1.
//         //    cyclePos only needs a +/-1 hedge here (not a wide window): the
//         //    coarse stage above already scanned every cyclePos exhaustively
//         //    at its chosen phase, so this is only covering the case where a
//         //    refined (in-between) phase shifts the ideal cyclePos by one.
//         List<CompletableFuture<Void>> refineFutures = new ArrayList<>();
//         for (WatermarkConfigRepository.DetectionConfigProjection c : sessionConfigs) {
//             BestAlignment ba = bestByUser.get(c.getUserId());
//             long hopsPerCycle = hopsPerCycleByUser.get(c.getUserId());
//             long cycleStride = 1;

//             int phaseLo = Math.max(0, ba.phase - phaseStride);
//             int phaseHi = Math.min(hop - 1, ba.phase + phaseStride);
//             long cycleLo = Math.max(0, ba.cyclePos - cycleStride);
//             long cycleHi = Math.min(hopsPerCycle - 1, ba.cyclePos + cycleStride);

//             int numPhases = phaseHi - phaseLo + 1;
//             long numCycles = cycleHi - cycleLo + 1;
//             long totalCells = (long) numPhases * numCycles;
//             int chunks = (int) Math.min(numWorkers, Math.max(1, totalCells));
//             long cellChunkSize = (totalCells + chunks - 1) / chunks;

//             for (int ci = 0; ci < chunks; ci++) {
//                 long startCell = (long) ci * cellChunkSize;
//                 long endCellExclusive = Math.min(totalCells, startCell + cellChunkSize);
//                 if (startCell >= endCellExclusive) continue;

//                 refineFutures.add(CompletableFuture.runAsync(() -> {
//                     ScratchBuffers buffers = new ScratchBuffers(analysisSize, maxReconLen);
//                     double localBestScore = Double.NEGATIVE_INFINITY;
//                     int localBestPhase = phaseLo;
//                     long localBestCyclePos = cycleLo;
//                     // Cache analysis per phase within this chunk, since
//                     // consecutive cells often share the same phase and
//                     // recomputing it per cyclePos would be wasteful.
//                     int cachedPhase = Integer.MIN_VALUE;
//                     WatermarkDsp.FrameAnalysis cachedAnalysis = null;

//                     for (long cell = startCell; cell < endCellExclusive; cell++) {
//                         int phase = phaseLo + (int) (cell / numCycles);
//                         long cyclePos = cycleLo + (cell % numCycles);

//                         int framesAvailable = Math.min(searchFrameCount, (samples.length - phase) / hop);
//                         if (framesAvailable <= 0) continue;

//                         if (phase != cachedPhase) {
//                             cachedAnalysis = WatermarkDsp.analyzeAudioFrames(
//                                     samples, phase, hop, analysisSize, numBands, window, binToBand, framesAvailable);
//                             cachedPhase = phase;
//                         }

//                         double score = scoreCandidate(
//                                 samples, phase, cachedAnalysis, c, cyclePos, hopsPerCycle, hop, analysisSize,
//                                 numBands, window, binToBand, framesAvailable, buffers);
//                         if (score > localBestScore) {
//                             localBestScore = score;
//                             localBestPhase = phase;
//                             localBestCyclePos = cyclePos;
//                         }
//                     }
//                     updateBestAlignment(ba, localBestScore, localBestPhase, localBestCyclePos);
//                 }, userScoringExecutor));
//             }
//         }
//         refineFutures.forEach(future -> future.join());

//         // ── Stage 3: final score at the pinpointed alignment, over the
//         //    WHOLE recording — this is what's actually compared against
//         //    the detection threshold in WatermarkDetectionService. One task
//         //    per user is fine here — it's a single evaluation each, not a
//         //    search.
//         Map<String, Double> finalScores = new ConcurrentHashMap<>();
//         List<CompletableFuture<Void>> finalFutures = new ArrayList<>(sessionConfigs.size());
//         for (WatermarkConfigRepository.DetectionConfigProjection c : sessionConfigs) {
//             finalFutures.add(CompletableFuture.runAsync(() -> {
//                 BestAlignment ba = bestByUser.get(c.getUserId());
//                 int framesAvailable = (samples.length - ba.phase) / hop;
//                 if (framesAvailable <= 0) {
//                     finalScores.put(c.getUserId(), 0.0);
//                     return;
//                 }

//                 ScratchBuffers buffers = new ScratchBuffers(analysisSize, maxReconLen);
//                 WatermarkDsp.FrameAnalysis analysis = WatermarkDsp.analyzeAudioFrames(
//                         samples, ba.phase, hop, analysisSize, numBands, window, binToBand, framesAvailable);
//                 long hopsPerCycle = hopsPerCycleByUser.get(c.getUserId());
//                 double score = scoreCandidate(
//                         samples, ba.phase, analysis, c, ba.cyclePos, hopsPerCycle, hop, analysisSize,
//                         numBands, window, binToBand, framesAvailable, buffers);

//                 finalScores.put(c.getUserId(), score);
//             }, userScoringExecutor));
//         }
//         finalFutures.forEach(future -> future.join());

//         // Preserve original registration order in the returned map (used
//         // for display ordering downstream), since ConcurrentHashMap doesn't.
//         Map<String, Double> ordered = new LinkedHashMap<>();
//         for (WatermarkConfigRepository.DetectionConfigProjection c : sessionConfigs) {
//             ordered.put(c.getUserId(), finalScores.get(c.getUserId()));
//         }
//         return ordered;
//     }

//     private double scoreCandidate(
//             float[] samples, int phase, WatermarkDsp.FrameAnalysis analysis,
//             WatermarkConfigRepository.DetectionConfigProjection config, long cyclePos, long hopsPerCycle,
//             int hop, int analysisSize, int numBands, float[] window, int[] binToBand,
//             int frameCount, ScratchBuffers buffers) {

//         long baseSeedState = WatermarkDsp.resolveSeed(config.getSeed());
//         double marginLinear = Math.pow(10.0, config.getAlpha() / 20.0);
//         return WatermarkDsp.scoreWithAnalysis(
//                 samples, phase, analysis, baseSeedState, cyclePos, hopsPerCycle, hop, analysisSize,
//                 numBands, window, binToBand, marginLinear, frameCount,
//                 buffers.pnRe, buffers.pnIm, buffers.recon, buffers.prngState);
//     }
// }

package com.convo.audio_watermark.service;

import com.convo.audio_watermark.repository.WatermarkConfigRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(WatermarkSearchEngine.class);

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
     * DRIFT TRACKING (final scoring stage).
     *
     * Stages 1/2 above only ever look at the FIRST ~SEARCH_FRAME_COUNT
     * frames (well under a second) to pin down one (phase, cyclePos)
     * alignment. A single fixed-stride extrapolation of that alignment
     * across the WHOLE recording is exact for anything that stays in the
     * digital domain end-to-end (direct WAV capture off the same mix bus,
     * or an MP3 transcode of that same file), but breaks down the moment
     * the recording involves a real acoustic hop: a phone or external
     * recorder samples the room air on its OWN microphone's ADC clock,
     * which shares no reference with the playback device's DAC clock. Two
     * independent consumer-grade clocks are essentially never
     * bit-identical — even a modest, entirely ordinary ~50-100ppm
     * mismatch accumulates real sample drift over time, and the
     * correlation peak decays to noise-floor within roughly +/-10 samples
     * of the true phase (see PHASE_COARSE_CANDIDATES above) — so once
     * accumulated drift exceeds that, every later frame is being compared
     * against audio it no longer lines up with. That's the mechanism
     * behind "direct WAV works, synthetic MP3 works, but a phone/external
     * recording of the same call doesn't" — see git history for the
     * original fix that introduced block-wise re-locking to track this.
     *
     * PERFORMANCE — WHY THIS NEEDED A REDESIGN: the first version of that
     * fix re-derived the masking analysis AND re-synthesized the
     * predicted watermark (2-3 FFTs per frame each) for EVERY candidate
     * offset it tried in EVERY block — an O(blocks x candidates x frames)
     * nested loop that was almost entirely redundant work: the masking
     * threshold and the synthesized watermark barely change across a
     * +/-32-sample shift, so recomputing both FROM SCRATCH per candidate
     * bought essentially nothing over computing them ONCE per block. For
     * a 22s clip (~22 one-second blocks, 65 candidates/block, ~120 FFTs
     * per candidate) that's on the order of 180,000 FFT-512 calls PER
     * REGISTERED USER for this stage alone, on top of stages 1/2's own
     * (pre-existing, unchanged) exhaustive cyclePos scan — cheap in
     * isolation, but with the fast-path/fallback pair, several users, and
     * a handful of parallel executor threads all doing this simultaneously,
     * it adds up to sustained, CPU-pegging work for minutes, not seconds.
     *
     * THE FIX: synthesize the predicted watermark ONCE per block (the
     * expensive, FFT-based part), then do the local phase search by
     * sliding that ALREADY-COMPUTED reconstruction against the recording
     * at nearby sample offsets — a plain per-frame dot-product
     * (WatermarkDsp.correlateOnly), no FFT involved. This is the exact
     * same "cross-correlate a fixed template against nearby shifts"
     * operation as before, just without needlessly rebuilding the
     * template for every shift tried. It turns the FFT-bound part of this
     * stage from O(blocks x candidates x frames) into O(blocks x frames)
     * — the (2*DRIFT_PHASE_RADIUS+1)-candidate search is now pure
     * arithmetic, roughly two orders of magnitude cheaper per candidate
     * than an FFT pair, so widening it further costs almost nothing.
     *
     * cyclePos itself still needs no per-block or per-candidate search:
     * it's a pure frame count from the original lock (the embedder's PN
     * sequence advances exactly one step per hop by construction), so it
     * stays exact regardless of real-world clock drift — only the mapping
     * from frame count to RECORDED SAMPLE OFFSET drifts, which is exactly
     * what the per-block phase search corrects for.
     */
    private static final double DRIFT_BLOCK_SECONDS = 1.0;

    /**
     * Frames used for each block's local re-lock search. Now that the
     * search itself is FFT-free (see above), this only bounds the cost of
     * a cheap dot-product loop, not an FFT — kept modest purely so the
     * per-candidate probe stays representative of local SNR without
     * scanning the whole block for every one of the 2*DRIFT_PHASE_RADIUS+1
     * candidates.
     */
    private static final int DRIFT_SEARCH_FRAMES = 40;

    /**
     * How far (in samples, either direction) each block's local re-lock
     * search looks around the extrapolated offset. Sized well above the
     * worst realistic per-block drift (~24 samples/block at a generous
     * 500ppm clock error and a 1s block — see DRIFT_BLOCK_SECONDS), not
     * just the ~10-sample width the correlation peak itself needs, so a
     * block with worse-than-typical drift still falls inside the search
     * window instead of losing lock entirely. Cheap to widen if needed —
     * see the performance note above.
     */
    private static final int DRIFT_PHASE_RADIUS = 32;

    /**
     * ROOT CAUSE OF BOTH SYMPTOMS SEEN SO FAR — "genuine signal scores
     * near the noise floor" AND, worse, "an unrelated user sometimes
     * outranks the real recorder": SELECTION BIAS between the SEARCH
     * (argmax over many candidates) and the SCORE (the number actually
     * compared against DETECTION_THRESHOLD / used to rank users).
     *
     * Every stage of this pipeline — stage 1's coarse phase x cyclePos
     * scan, stage 2's local refine, AND this stage's own per-block local
     * re-lock search — works by trying many candidate alignments and
     * keeping whichever one correlates best. That's necessary and correct
     * for LOCATING an alignment. But if the SAME audio that was searched
     * over is then also used to REPORT that alignment's score, the result
     * is a textbook look-elsewhere-effect: with N candidates tried on one
     * fixed slice of audio, order statistics guarantee SOME candidate
     * correlates unusually well with that specific slice purely by
     * chance — for literally ANY seed, correct or not. Stage 1/2 alone
     * try on the order of a few thousand (phase, cyclePos) hypotheses per
     * user on the recording's FIRST ~SEARCH_FRAME_COUNT frames; with that
     * many draws, a decent-looking peak is close to guaranteed for every
     * registered user, whether or not they actually recorded anything.
     * Scoring anywhere near that same window — which the earlier version
     * of this method did, starting every user's block walk right at
     * lockPhase — bakes that chance-driven inflation directly into the
     * reported score. In a long, plain, equal-weight average over
     * thousands of frames (the very first version of this stage) that
     * inflated window was a small fraction of the total and got diluted
     * into near-irrelevance. But confidence-gated tracking and
     * energy-weighted aggregation (added to fix the dilution problem)
     * both concentrate influence onto FEWER, "more confident" / "louder"
     * blocks — and the search-selected window is, by construction, one of
     * the most confident-LOOKING and (since its reconstruction was
     * chosen specifically to match that audio) often one of the
     * loudest-scoring blocks in the whole recording, for EVERY user. That
     * turned a previously-diluted-into-harmlessness bias into one that
     * can dominate the aggregate and flip the ranking — exactly the "wrong
     * user wins" regression.
     *
     * THE FIX has two parts, both about keeping SEARCH and SCORE on
     * disjoint audio:
     * (a) HELD-OUT SCORING WINDOW — this method's block walk starts AFTER
     *     skipping the exact frames stage 1/2 searched over (see
     *     lockSearchFrames), not at lockPhase itself. Every frame this
     *     method ever scores is audio that was NEVER used to choose
     *     anything, for any user — so whatever correlation shows up
     *     there reflects the recording's actual content, not a search
     *     procedure's ability to find a coincidence.
     * (b) PER-BLOCK SEARCH/SCORE SPLIT — the same principle applied
     *     locally: each block's own re-lock search (still needed to track
     *     drift — see DRIFT_SEARCH_FRAMES) uses only the block's FIRST
     *     DRIFT_SEARCH_FRAMES frames; the block's contribution to the
     *     final score is computed from the REMAINING, disjoint frames of
     *     that same block, at whichever offset the search chose. A block
     *     never scores the exact frames it searched over.
     *
     * On top of that, the pre-existing fixes are kept, now operating on
     * bias-free data:
     * (c) CONFIDENCE-GATED TRACKING — a block's local search result only
     *     gets to correct the running phase when its peak clearly stands
     *     out (z-score test, DRIFT_CONFIDENCE_Z) from the OTHER
     *     candidates tried in that same search, so one noisy block can't
     *     throw every later block's search off-center.
     * (d) ENERGY-WEIGHTED AGGREGATION — WatermarkDsp.ScoreDetail.
     *     weightedAverage() combines every scored (and now bias-free)
     *     frame, letting louder/more-reliable frames count for more than
     *     near-silent ones, without diluting toward zero OR risking
     *     inflation from search artifacts.
     *
     * DRIFT_CONFIDENCE_Z is set relative to DRIFT_PHASE_RADIUS: with
     * candidateCount = 2*DRIFT_PHASE_RADIUS+1 = 65 roughly-independent
     * noise draws per block, order statistics put the EXPECTED max
     * z-score from pure noise around sqrt(2*ln(65)) ≈ 2.9 — so a
     * threshold much below ~3 would call a large fraction of pure-noise
     * blocks "confident" by construction. 4.0 leaves a real margin above
     * that expected noise ceiling.
     */
    private static final double DRIFT_CONFIDENCE_Z = 4.0;

    /**
     * WHY DETECTION WORKS AT ~2s BUT DEGRADES/INVERTS BY ~5s (duration
     * accumulation): the earlier drift tracker corrected phase
     * INCREMENTALLY off a CUMULATIVE running offset — each block searched
     * +/-DRIFT_PHASE_RADIUS around wherever the PREVIOUS block left off,
     * and advanced from there. Two things make that fail as the recording
     * gets longer, while looking fine on a short one:
     *
     * 1. THE CORRECTION CAN'T KEEP UP, SO ERROR ACCUMULATES. Real
     *    per-block correlation from a phone/MP3 recording is weak, so the
     *    confidence gate (rightly) fires only on the stronger blocks;
     *    weaker blocks "coast" on the fixed hop stride. But the recorder's
     *    clock is drifting the WHOLE time, so every coasted block lets the
     *    running offset fall a little further behind the true content. On
     *    a 2s clip there are only ~2 blocks and the total drift is a
     *    handful of samples — inside the peak, nothing to correct, so it
     *    "just works." By ~5s the accumulated gap grows past the
     *    correlation peak's ~10-sample half-width for a growing fraction
     *    of blocks (measured: ~100% of blocks still aligned at 2s vs only
     *    ~60% at 5s in a drift simulation), and by ~10s the running offset
     *    walks clean outside the +/-DRIFT_PHASE_RADIUS window and lock is
     *    lost for the entire tail of the recording.
     * 2. THE SEARCH ITSELF INJECTS A RANDOM WALK. On a weak/near-noise
     *    block that does happen to pass the gate, the "best" of
     *    2*DRIFT_PHASE_RADIUS+1 candidates is partly a noise pick, nudging
     *    the cumulative offset by an essentially random delta. Fed forward
     *    as the next block's search center, those deltas random-walk the
     *    anchor away from truth over time — and, because phase was nudged
     *    while cyclePos advanced by an exact frame count, a large enough
     *    accumulated nudge also desyncs phase from cyclePos (the
     *    "reusing an incorrect PN position after some time" symptom).
     *
     * Once the tail of a long recording falls out of alignment, those
     * frames carry full audio ENERGY but ZERO real correlation. In the
     * weightedAverage combiner they land in sum(denom) with nothing in
     * sum(rawCorr), so they DILUTE the genuine early signal downward — and
     * since a wrong user's score is ~0 either way, a diluted-enough
     * correct user can drop below both the threshold AND a wrong user's
     * noise. That is the 5s regression: not a weaker watermark, but later
     * frames going out of alignment and dragging the aggregate down.
     *
     * THE FIX — ABSOLUTE PREDICTION VIA A DRIFT-SLOPE MODEL (no
     * cumulative state, so nothing can accumulate):
     *  - Every block's search is centered on an ABSOLUTE prediction from
     *    the ORIGINAL held-out lock: predicted offset for the block at
     *    logical frame F is (holdoutPhase + F*hop) plus an estimated drift
     *    of (driftSlope * F) samples. There is no running offset to walk;
     *    each block's center is recomputed from F, so a bad block can't
     *    corrupt any later block's center.
     *  - driftSlope (samples of drift per elapsed frame) is fit through
     *    only the CONFIDENT blocks, via a through-origin least-squares
     *    slope over their (F, observedDrift) points — observedDrift =
     *    bestOffset - (holdoutPhase + F*hop). A clock's drift rate is
     *    essentially constant, so even 2-3 confident blocks anywhere in
     *    the recording pin the slope for EVERY block, including weak ones
     *    that never locked on their own. Longer recordings give MORE
     *    confident points, so the slope estimate — and thus alignment —
     *    only gets BETTER with duration, the opposite of the old behavior.
     *  - cyclePos remains a pure function of F (holdoutCyclePos + F, mod
     *    hopsPerCycle) and the predicted phase is consistent with it by
     *    construction, so phase and PN position can no longer desync.
     *  - The +/-DRIFT_PHASE_RADIUS per-block search still runs, and each
     *    block is SCORED at its own search-portion argmax (on disjoint
     *    held-out frames, so it stays unbiased — see the per-block split
     *    in driftTrackedFinalScore), which captures a real but
     *    sub-confidence peak wherever it lands in the window. The
     *    confidence gate is used only to decide which blocks feed the
     *    slope fit, so noise can't steer the drift model even though every
     *    in-window peak still contributes to the score.
     *
     * With alignment held across the whole recording, every scored frame
     * carries the repeating watermark, so weightedAverage stays stable
     * with duration (longer is at worst neutral, generally better) — which
     * is why this fix needs no threshold change. The standardized
     * ScoreDetail.detectionStat() is logged alongside as a duration-robust
     * cross-check.
     */
    private static final double DRIFT_SLOPE_MAX_SAMPLES_PER_FRAME = 0.05;

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

        long searchStartNanos = System.nanoTime();
        int numDetectorFrames = samples.length / hop;
        int searchFrameCount = Math.min(SEARCH_FRAME_COUNT, numDetectorFrames);

        int phaseStride = Math.max(1, hop / PHASE_COARSE_CANDIDATES);
        int maxReconLen = numDetectorFrames * hop + analysisSize;
        String passLabel = cyclePosLimit != null ? "cyclePosLimit=" + cyclePosLimit : "exhaustive";

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
        long stage1Nanos = System.nanoTime();
        log.info("watermark-detect[{}] stage1 (coarse phase x cyclePos scan) took {} ms",
                passLabel, (stage1Nanos - searchStartNanos) / 1_000_000);

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
        long stage2Nanos = System.nanoTime();
        log.info("watermark-detect[{}] stage2 (local refine) took {} ms",
                passLabel, (stage2Nanos - stage1Nanos) / 1_000_000);

        // ── Stage 3: final score over the WHOLE recording, drift-tracked —
        //    this is what's actually compared against the detection
        //    threshold in WatermarkDetectionService. One task per user
        //    (each user's block walk is inherently sequential — see
        //    DRIFT_BLOCK_SECONDS doc — so parallelism is across users only,
        //    same as before). See DRIFT_BLOCK_SECONDS doc above: this is
        //    O(blocks x frames) FFT work + a cheap O(blocks x candidates)
        //    correlation-only search, not O(blocks x candidates x frames)
        //    FFT work.
        Map<String, Double> finalScores = new ConcurrentHashMap<>();
        List<CompletableFuture<Void>> finalFutures = new ArrayList<>(sessionConfigs.size());
        for (WatermarkConfigRepository.DetectionConfigProjection c : sessionConfigs) {
            finalFutures.add(CompletableFuture.runAsync(() -> {
                BestAlignment ba = bestByUser.get(c.getUserId());
                long hopsPerCycle = hopsPerCycleByUser.get(c.getUserId());
                double score = driftTrackedFinalScore(
                        samples, ba.phase, ba.cyclePos, c, hopsPerCycle, hop, analysisSize,
                        numBands, window, binToBand, sampleRate, c.getUserId(), searchFrameCount);
                finalScores.put(c.getUserId(), score);
            }, userScoringExecutor));
        }
        finalFutures.forEach(future -> future.join());
        long stage3Nanos = System.nanoTime();
        log.info("watermark-detect[{}] stage3 (drift-tracked final score, {} user(s)) took {} ms",
                passLabel, sessionConfigs.size(), (stage3Nanos - stage2Nanos) / 1_000_000);
        log.info("watermark-detect[{}] findBestScoresAcrossUsers total: {} ms",
                passLabel, (stage3Nanos - searchStartNanos) / 1_000_000);

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

    /**
     * Walks the whole recording forward from the stage 1/2 lock in
     * {@code DRIFT_BLOCK_SECONDS}-long blocks, re-estimating each block's
     * sample-level phase with a small local search before scoring it, and
     * combining every block's contribution into one overall
     * ENERGY-WEIGHTED average (see DRIFT_CONFIDENCE_Z doc above for why a
     * plain average dilutes real signal, and why a block's local search
     * result is only trusted to re-anchor tracking when it's clearly
     * above the noise floor).
     *
     * COST: exactly ONE masking analysis + ONE watermark synthesis per
     * block (the only FFT-bearing work here) — everything else (the local
     * phase search AND the final per-block score) reuses that same
     * synthesized reconstruction via cheap dot-product correlation
     * (WatermarkDsp.correlateOnly). So this method is O(blocks x frames)
     * in FFT work, plus O(blocks x candidates x hop) in plain arithmetic
     * — no nested loop ever re-triggers an FFT per candidate.
     *
     * cyclePos is a pure function of the logical frame index F from the
     * held-out lock (holdoutCyclePos + F, mod hopsPerCycle) — never
     * incrementally walked — so it can never desync from the predicted
     * phase, and the predicted phase itself is an ABSOLUTE function of F
     * plus a fitted drift slope (see DRIFT_SLOPE_MAX_SAMPLES_PER_FRAME
     * doc), so alignment error cannot accumulate across blocks.
     *
     * Buffers are allocated ONCE per call — sized to a single block, not
     * the whole recording — and reused (overwritten in place) across
     * every block in the walk, so a minute-long recording doesn't
     * multiply allocation cost by its length either.
     */
    private double driftTrackedFinalScore(
            float[] samples, int lockPhase, long lockCyclePos,
            WatermarkConfigRepository.DetectionConfigProjection config, long hopsPerCycle,
            int hop, int analysisSize, int numBands, float[] window, int[] binToBand,
            float sampleRate, String userId, int lockSearchFrames) {

        // ── Held-out starting point: skip the EXACT frames stage 1/2 used
        //    to CHOOSE (lockPhase, lockCyclePos) via their own argmax
        //    search — see DRIFT_CONFIDENCE_Z doc above for why scoring
        //    that same window would be a selection-bias trap. If the
        //    recording is barely longer than the lock-search window (rare
        //    — only very short clips), fall back to scoring from the raw
        //    lock point rather than refusing to score at all; that
        //    reintroduces the bias risk only for those unusually short
        //    recordings, and is logged so it's visible when it happens.
        int holdoutPhase = lockPhase + lockSearchFrames * hop;
        long holdoutCyclePos = (lockCyclePos + lockSearchFrames) % hopsPerCycle;
        if ((samples.length - holdoutPhase) / hop <= 0) {
            log.warn("watermark-detect user={} recording too short for held-out scoring "
                    + "past the lock-search window — falling back to the raw lock point "
                    + "(selection-bias risk applies only to this short-clip case)", userId);
            holdoutPhase = lockPhase;
            holdoutCyclePos = lockCyclePos;
        }

        int blockFrames = Math.max(1, blockFramesFor(hop, sampleRate));
        int blockReconLen = blockFrames * hop + analysisSize;

        double[] pnRe = new double[analysisSize];
        double[] pnIm = new double[analysisSize];
        double[] recon = new double[blockReconLen];
        long[] prngState = new long[1];
        int candidateCount = 2 * DRIFT_PHASE_RADIUS + 1;
        double[] candidateScores = new double[candidateCount];

        long baseSeedState = WatermarkDsp.resolveSeed(config.getSeed());
        double marginLinear = Math.pow(10.0, config.getAlpha() / 20.0);

        // ── Drift-slope model, fit online through CONFIDENT blocks only.
        //    observedDrift at logical frame F = bestOffset - (holdoutPhase
        //    + F*hop). A clock's drift rate is ~constant, so a through-
        //    origin least-squares slope (sum(F*drift)/sum(F*F)) over those
        //    points predicts drift for EVERY block, including weak ones
        //    that never lock on their own. No cumulative offset exists to
        //    accumulate error — every block's center is recomputed from F.
        double slopeNum = 0.0;   // Σ F * observedDrift
        double slopeDen = 0.0;   // Σ F * F
        double driftSlope = 0.0; // samples of drift per elapsed frame

        WatermarkDsp.ScoreDetail total = WatermarkDsp.ScoreDetail.zero();
        int blocksLocked = 0, blocksCoasted = 0;
        int frameIndex = 0; // logical frames elapsed since the held-out start

        while (true) {
            // ── ABSOLUTE predicted read offset for this block from the
            //    original lock + fitted drift — NOT a running cumulative
            //    offset, so a bad block can't corrupt any later block.
            int predictedPhase = holdoutPhase + frameIndex * hop
                    + (int) Math.round(driftSlope * frameIndex);
            int framesRemaining = (samples.length - predictedPhase) / hop;
            if (predictedPhase < 0 || framesRemaining <= 0) break;
            int framesThisBlock = Math.min(blockFrames, framesRemaining);

            long cyclePos = (holdoutCyclePos + frameIndex) % hopsPerCycle;

            // ── ONE FFT-based pass per block: masking analysis + PN
            //    synthesis at the predicted offset. Reused below for BOTH
            //    the local search and the final score — never recomputed
            //    per candidate.
            WatermarkDsp.FrameAnalysis analysis = WatermarkDsp.analyzeAudioFrames(
                    samples, predictedPhase, hop, analysisSize, numBands, window, binToBand, framesThisBlock);
            WatermarkDsp.synthesizeRecon(
                    analysis, baseSeedState, cyclePos, hopsPerCycle, hop, analysisSize,
                    window, binToBand, marginLinear, framesThisBlock, pnRe, pnIm, recon, prngState);

            // ── Per-block search/score split: the local re-lock search
            //    below only ever looks at the block's FIRST searchFrames
            //    frames; this block's contribution to the final score
            //    (further down) is computed from the REMAINING, disjoint
            //    frames — never the ones the search itself evaluated. See
            //    DRIFT_CONFIDENCE_Z doc above for why this matters.
            int searchFrames = Math.min(DRIFT_SEARCH_FRAMES, Math.max(1, framesThisBlock / 2));
            int scoreStartFrame = searchFrames;
            int scoreFrameCount = framesThisBlock - searchFrames;
            if (scoreFrameCount <= 0) {
                scoreStartFrame = 0;
                scoreFrameCount = framesThisBlock;
            }

            // ── Local re-lock probe around the PREDICTED offset: slide the
            //    SAME reconstruction against the recording at nearby sample
            //    offsets, using ONLY the search portion. Pure dot-product
            //    correlation, no FFT. This now only mops up the small
            //    residual between the slope prediction and truth (and
            //    supplies fresh confident points to the slope fit).
            int bestOffset = predictedPhase;
            double bestScore = Double.NEGATIVE_INFINITY;
            int validCandidates = 0;
            double scoreSum = 0.0;

            for (int i = 0; i < candidateCount; i++) {
                int d = i - DRIFT_PHASE_RADIUS;
                int candidateOffset = predictedPhase + d;
                if (candidateOffset < 0 || (samples.length - candidateOffset) / hop < searchFrames) {
                    candidateScores[i] = Double.NaN;
                    continue;
                }
                double score = WatermarkDsp.correlateOnly(
                        samples, candidateOffset, recon, hop, 0, searchFrames).average();
                candidateScores[i] = score;
                scoreSum += score;
                validCandidates++;
                if (score > bestScore) {
                    bestScore = score;
                    bestOffset = candidateOffset;
                }
            }

            // ── Confidence gate: only a block whose peak clearly clears
            //    the noise floor of the OTHER candidates in the SAME search
            //    (z-score test) is trusted to feed the drift-slope fit.
            //    Weak/near-noise blocks are still SCORED — at the slope
            //    prediction — they just don't get a vote in the drift
            //    model, so noise can't steer alignment.
            boolean confident = false;
            if (validCandidates > 1) {
                double mean = scoreSum / validCandidates;
                double variance = 0.0;
                for (int i = 0; i < candidateCount; i++) {
                    if (Double.isNaN(candidateScores[i])) continue;
                    double diff = candidateScores[i] - mean;
                    variance += diff * diff;
                }
                variance /= validCandidates;
                double std = Math.sqrt(variance);
                confident = std > 1e-9 && (bestScore - mean) > DRIFT_CONFIDENCE_Z * std;
            }

            if (confident && frameIndex > 0) {
                // Feed (F, observedDrift) into the through-origin slope fit,
                // then re-derive the slope (clamped to a sane clock-drift
                // magnitude so a single bad point can't fling predictions
                // out of the search window).
                double observedDrift = bestOffset - (holdoutPhase + (double) frameIndex * hop);
                slopeNum += (double) frameIndex * observedDrift;
                slopeDen += (double) frameIndex * frameIndex;
                if (slopeDen > 0) {
                    double s = slopeNum / slopeDen;
                    if (s > DRIFT_SLOPE_MAX_SAMPLES_PER_FRAME) s = DRIFT_SLOPE_MAX_SAMPLES_PER_FRAME;
                    if (s < -DRIFT_SLOPE_MAX_SAMPLES_PER_FRAME) s = -DRIFT_SLOPE_MAX_SAMPLES_PER_FRAME;
                    driftSlope = s;
                }
            }
            if (confident) blocksLocked++; else blocksCoasted++;

            // ── Score this block using the DISJOINT, held-out portion, at
            //    the block's OWN search-portion argmax (bestOffset). This
            //    captures a real but sub-confidence peak wherever it landed
            //    inside the search window — which is the common case for a
            //    weak phone/MP3 watermark and is exactly what the earlier
            //    "score at the prediction unless confident" version missed,
            //    leaving weak-but-real blocks scored off-peak. It stays
            //    unbiased because bestOffset was chosen on the search
            //    frames while the score uses the DISJOINT held-out frames
            //    (see the per-block split above): the offset that maximizes
            //    correlation on one set carries no information that inflates
            //    correlation on the other, so a pure-noise block's random
            //    argmax scores ~0 here rather than a spurious positive.
            int scoreOffset = bestOffset;
            WatermarkDsp.ScoreDetail detail = WatermarkDsp.correlateOnly(
                    samples, scoreOffset, recon, hop, scoreStartFrame, scoreFrameCount);
            total = WatermarkDsp.ScoreDetail.combine(total, detail);

            frameIndex += framesThisBlock;
        }

        double weighted = total.weightedAverage();
        log.info("watermark-detect user={} drift-tracking: {} block(s) locked, {} coasted, "
                        + "driftSlope={} samp/frame, weightedScore={}, detStat={}, "
                        + "simpleAvgScore={} (held-out frames={})",
                userId, blocksLocked, blocksCoasted,
                String.format("%.5f", driftSlope),
                String.format("%.6f", weighted),
                String.format("%.2f", total.detectionStat()),
                String.format("%.6f", total.average()), total.scoredFrames);
        return weighted;
    }

    /** DRIFT_BLOCK_SECONDS expressed in frames, from the decoded audio's actual sample rate. */
    private int blockFramesFor(int hop, float sampleRate) {
        double framesPerSecond = sampleRate / hop;
        return Math.max(1, (int) Math.round(DRIFT_BLOCK_SECONDS * framesPerSecond));
    }
}