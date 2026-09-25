package com.convo.audio_watermark.service;

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
 *    small and FIXED regardless of call length (750 positions for the current
 *    4-second cycle at a 256-sample hop / 48kHz) — but must be scanned
 *    EXHAUSTIVELY (see stage 1 below): each step jumps the PRNG by a full
 *    analysisSize draws, so neighboring cyclePos values are statistically
 *    independent of each other, with no partial correlation to guide a
 *    coarse-then-refine search toward the true one.
 * 2. phase — the exact sample offset within a hop the recording begins at.
 *    Unlike cyclePos, neighboring phases share most of the same underlying
 *    audio samples in their analysis window, so the score varies smoothly
 *    here — a coarse grid + local refine is valid for this dimension.
 *
 * Every user's PN spectrum for every cyclePos is built ONCE per search (see
 * WatermarkDsp.PnSpectra) since it depends only on (seed, cyclePos), and
 * the audio-side masking-threshold analysis for a given phase is shared
 * across every user and every cyclePos candidate tested at that phase (see
 * WatermarkDsp.analyzeAudioFrames) since it depends only on the recorded
 * samples. What's left per candidate frame is one inverse FFT (the masking
 * gains are audio-dependent) plus the correlation.
 *
 * Each user's cycle length comes from their own stored config
 * (DetectionConfigView.getCycleSeconds()) and must match the cycleSeconds
 * their embedder was actually given.
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
     * search looks around the predicted offset. Sized above the worst
     * realistic per-block drift (~24 samples/block at a generous 500ppm
     * clock error and a 1s block — see DRIFT_BLOCK_SECONDS), so a block
     * whose prediction lags still finds its peak inside the window. Note
     * the peak itself is BROAD on real, band-limited recordings: measured
     * falling to zero only ~28 samples either side of the true offset, so
     * it fills most of this window (see DRIFT_LOCK_MIN_TSTAT for why that
     * matters). Cheap to widen if needed — see the performance note above.
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
     * (c) CONFIDENCE-GATED TRACKING — only blocks whose lock is clearly
     *     real feed the drift model (see DRIFT_MAX_PPM), so noise can't
     *     steer alignment. A block counts as locked when the t-statistic
     *     of its HELD-OUT frames at the chosen offset
     *     (ScoreDetail.detectionStat()) is at least this threshold, and
     *     the chosen offset isn't at the window edge (an edge argmax
     *     usually means the true peak lies outside the window, so the
     *     offset is a biased reading).
     *
     *     This replaced a z-score test of the best candidate against the
     *     OTHER candidates in the same search, which in practice never
     *     passed (0 of 20 blocks locked at every drift level tested): the
     *     correlation peak on real audio is so broad (see
     *     DRIFT_PHASE_RADIUS) that "the other candidates" are mostly the
     *     peak's own flanks, so that z-score sat around 1.6 however strong
     *     the watermark. Significance across the held-out FRAMES doesn't
     *     depend on the peak's shape, and because the offset was chosen on
     *     different frames it carries no selection bias: for wrong seeds
     *     it behaves like N(0,1) (measured max 2.75 over 318 blocks),
     *     while genuinely aligned blocks measured 6.3 and up on a
     *     synthetic 20-60s test set with realistic drift and room noise.
     *     4.0 sits between the two.
     * (d) ENERGY-WEIGHTED AGGREGATION — WatermarkDsp.ScoreDetail.
     *     weightedAverage() combines every scored (and now bias-free)
     *     frame, letting louder/more-reliable frames count for more than
     *     near-silent ones, without diluting toward zero OR risking
     *     inflation from search artifacts.
     */
    private static final double DRIFT_LOCK_MIN_TSTAT = 4.0;

    /**
     * A locked block whose observed drift is further than this (in samples)
     * from the fitted drift line is treated as an outlier and dropped from
     * the fit (see DriftFit). Genuine locks measured within 3 samples of
     * the true drift; the one wrong lock seen in testing (a correlation
     * side lobe, after the true peak had drifted out of the window) was 39
     * samples off.
     */
    private static final double DRIFT_FIT_MAX_RESIDUAL = 10.0;

    /**
     * How far (in samples, either direction) a block searches when it fails
     * to lock near its prediction, before giving up on it. Real recordings
     * don't only drift smoothly, they JUMP: browsers move live audio between
     * components in 10 ms chunks (480 samples at 48kHz), and a hand-off that
     * drops or repeats one shifts everything after it by exactly that much.
     * Measured on a real in-app recording: one +480-sample jump ~1.2s in,
     * then perfectly aligned to the end. The ±DRIFT_PHASE_RADIUS search can
     * never follow that, so without this the watermark is lost for the rest
     * of the recording. 1024 covers up to two chunks either way plus margin.
     * The search reuses the block's existing reconstruction (dot products
     * only, no FFT), so it's cheap enough to run on every unlocked block.
     */
    private static final int DRIFT_REACQUIRE_RADIUS = 1024;

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
     *    of (intercept + slope * F) samples. There is no running offset to
     *    walk; each block's center is recomputed from F, so a bad block
     *    can't corrupt any later block's center.
     *  - The drift line is a least-squares fit, WITH an intercept, through
     *    only the CONFIDENT blocks' (F, observedDrift) points —
     *    observedDrift = bestOffset - (holdoutPhase + F*hop) — with
     *    outlier rejection (see DriftFit). The intercept matters: drift
     *    has already built up by the first held-out block (the lock was
     *    measured over the preceding SEARCH_FRAME_COUNT frames), and an
     *    earlier through-origin fit misread that offset as slope, roughly
     *    doubling its first estimates. A clock's drift rate is essentially
     *    constant, so a few confident blocks anywhere in the recording pin
     *    the line for EVERY block, including weak ones that never locked
     *    on their own. Longer recordings give MORE confident points, so the
     *    estimate — and thus alignment — only gets BETTER with duration.
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
     *
     * The fitted slope is clamped to +/-DRIFT_MAX_PPM so no fit can fling
     * predictions somewhere absurd. 500ppm is the same generous bound
     * DRIFT_PHASE_RADIUS is sized for; the clamp used to sit at ~195ppm,
     * below what two cheap consumer clocks can drift apart.
     */
    private static final double DRIFT_MAX_PPM = 500.0;

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
            List<DetectionConfigView> sessionConfigs,
            int hop, int analysisSize, int numBands, float sampleRate,
            float[] window, int[] binToBand, Long cyclePosLimit) {

        long searchStartNanos = System.nanoTime();
        int numDetectorFrames = samples.length / hop;
        int searchFrameCount = Math.min(SEARCH_FRAME_COUNT, numDetectorFrames);

        int phaseStride = Math.max(1, hop / PHASE_COARSE_CANDIDATES);
        // Stages 1/2 never synthesize more than searchFrameCount frames per
        // candidate (stage 3 allocates its own block-sized buffers), so size
        // each task's scratch reconstruction to that, NOT to the whole
        // recording: one buffer is allocated per task, and a whole-recording
        // buffer is hundreds of MB per task for a multi-minute upload.
        int maxReconLen = searchFrameCount * hop + analysisSize;
        String passLabel = cyclePosLimit != null ? "cyclePosLimit=" + cyclePosLimit : "exhaustive";

        Map<String, BestAlignment> bestByUser = new ConcurrentHashMap<>();
        Map<String, Long> hopsPerCycleByUser = new HashMap<>();
        Map<String, WatermarkDsp.PnSpectra> pnByUser = new HashMap<>();
        for (DetectionConfigView c : sessionConfigs) {
            bestByUser.put(c.getUserId(), new BestAlignment());
            // Each user's cycle length in hops — MUST match what their
            // embedder used (config.cycleSeconds), not a shared constant,
            // in case that's ever configured per-user.
            long hopsPerCycle = Math.max(1, Math.round((c.getCycleSeconds() * sampleRate) / hop));
            hopsPerCycleByUser.put(c.getUserId(), hopsPerCycle);
            // Read-only after this point; shared by every task for this user.
            pnByUser.put(c.getUserId(), WatermarkDsp.buildPnSpectra(
                    WatermarkDsp.resolveSeed(c.getSeed()), hopsPerCycle, analysisSize));
        }

        // ── Stage 1: coarse phase scan × EXHAUSTIVE cyclePos scan. The
        //    audio-side masking analysis for a given phase is
        //    shared/sequential (cheap, one FFT pass over SEARCH_FRAME_COUNT
        //    frames); for
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

            for (DetectionConfigView c : sessionConfigs) {
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
                WatermarkDsp.PnSpectra pn = pnByUser.get(c.getUserId());

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
                                    samples, phaseF, analysis, c, pn, cyclePos, hopsPerCycle, hop, analysisSize,
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
        for (DetectionConfigView c : sessionConfigs) {
            BestAlignment ba = bestByUser.get(c.getUserId());
            long hopsPerCycle = hopsPerCycleByUser.get(c.getUserId());
            WatermarkDsp.PnSpectra pn = pnByUser.get(c.getUserId());
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
                                samples, phase, cachedAnalysis, c, pn, cyclePos, hopsPerCycle, hop, analysisSize,
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
        for (DetectionConfigView c : sessionConfigs) {
            finalFutures.add(CompletableFuture.runAsync(() -> {
                BestAlignment ba = bestByUser.get(c.getUserId());
                long hopsPerCycle = hopsPerCycleByUser.get(c.getUserId());
                double score = driftTrackedFinalScore(
                        samples, ba.phase, ba.cyclePos, c, pnByUser.get(c.getUserId()), hopsPerCycle, hop,
                        analysisSize, numBands, window, binToBand, sampleRate, c.getUserId(), searchFrameCount);
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
        for (DetectionConfigView c : sessionConfigs) {
            ordered.put(c.getUserId(), finalScores.get(c.getUserId()));
        }
        return ordered;
    }

    private double scoreCandidate(
            float[] samples, int phase, WatermarkDsp.FrameAnalysis analysis,
            DetectionConfigView config, WatermarkDsp.PnSpectra pn, long cyclePos, long hopsPerCycle,
            int hop, int analysisSize, int numBands, float[] window, int[] binToBand,
            int frameCount, ScratchBuffers buffers) {

        double marginLinear = Math.pow(10.0, config.getAlpha() / 20.0);
        return WatermarkDsp.scoreWithAnalysis(
                samples, phase, analysis, pn, cyclePos, hopsPerCycle, hop, analysisSize,
                numBands, window, binToBand, marginLinear, frameCount,
                buffers.pnRe, buffers.pnIm, buffers.recon);
    }

    /**
     * Walks the whole recording forward from the stage 1/2 lock in
     * {@code DRIFT_BLOCK_SECONDS}-long blocks, re-estimating each block's
     * sample-level phase with a small local search before scoring it, and
     * combining every block's contribution into one overall
     * ENERGY-WEIGHTED average (see DRIFT_LOCK_MIN_TSTAT doc above for why a
     * plain average dilutes real signal, and why a block's local search
     * result is only trusted to steer tracking when it's clearly above the
     * noise floor).
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
     * plus a fitted drift line (see DRIFT_MAX_PPM doc), so alignment error
     * cannot accumulate across blocks.
     *
     * Buffers are allocated ONCE per call — sized to a single block, not
     * the whole recording — and reused (overwritten in place) across
     * every block in the walk, so a minute-long recording doesn't
     * multiply allocation cost by its length either.
     */
    private double driftTrackedFinalScore(
            float[] samples, int lockPhase, long lockCyclePos,
            DetectionConfigView config, WatermarkDsp.PnSpectra pn, long hopsPerCycle,
            int hop, int analysisSize, int numBands, float[] window, int[] binToBand,
            float sampleRate, String userId, int lockSearchFrames) {

        // ── Held-out starting point: skip the EXACT frames stage 1/2 used
        //    to CHOOSE (lockPhase, lockCyclePos) via their own argmax
        //    search — see DRIFT_LOCK_MIN_TSTAT doc above for why scoring
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

        double marginLinear = Math.pow(10.0, config.getAlpha() / 20.0);

        // ── Drift model, fit online through CONFIDENT blocks only (see
        //    DriftFit). A clock's drift rate is ~constant, so it predicts
        //    drift for EVERY block, including weak ones that never lock on
        //    their own. No cumulative offset exists to accumulate error —
        //    every block's center is recomputed from F.
        DriftFit drift = new DriftFit(DRIFT_MAX_PPM * 1e-6 * hop);

        WatermarkDsp.ScoreDetail total = WatermarkDsp.ScoreDetail.zero();
        int blocksLocked = 0, blocksCoasted = 0, blocksReacquired = 0;
        int frameIndex = 0; // logical frames elapsed since the held-out start

        while (true) {
            // ── ABSOLUTE predicted read offset for this block from the
            //    original lock + fitted drift — NOT a running cumulative
            //    offset, so a bad block can't corrupt any later block.
            int predictedPhase = holdoutPhase + frameIndex * hop
                    + (int) Math.round(drift.predict(frameIndex));
            int framesRemaining = (samples.length - predictedPhase) / hop;
            if (predictedPhase < 0 || framesRemaining <= 0) break;
            int framesThisBlock = Math.min(blockFrames, framesRemaining);

            long cyclePos = (holdoutCyclePos + frameIndex) % hopsPerCycle;

            // ── Per-block search/score split: the local re-lock search
            //    below only ever looks at the block's FIRST searchFrames
            //    frames; this block's contribution to the final score
            //    (further down) is computed from the REMAINING, disjoint
            //    frames — never the ones the search itself evaluated. See
            //    DRIFT_LOCK_MIN_TSTAT doc above for why this matters.
            int searchFrames = Math.min(DRIFT_SEARCH_FRAMES, Math.max(1, framesThisBlock / 2));
            int scoreStartFrame = searchFrames;
            int scoreFrameCount = framesThisBlock - searchFrames;
            if (scoreFrameCount <= 0) {
                scoreStartFrame = 0;
                scoreFrameCount = framesThisBlock;
            }

            BlockLock lock = lockBlock(samples, predictedPhase, cyclePos, hopsPerCycle, framesThisBlock,
                    searchFrames, scoreStartFrame, scoreFrameCount, pn, hop, analysisSize, numBands,
                    window, binToBand, marginLinear, pnRe, pnIm, recon);

            // ── Re-acquisition (see DRIFT_REACQUIRE_RADIUS): no lock near
            //    the prediction can mean the audio JUMPED rather than faded.
            //    Search much wider with the reconstruction just built, and if
            //    the block locks cleanly there, take that result and restart
            //    the drift model from it. The retry is only accepted when its
            //    own held-out frames confirm it (same gate as any lock), so
            //    for pure noise this almost never replaces the local result.
            boolean reacquired = false;
            if (!lock.confident()) {
                int wide = bestSearchOffset(samples, predictedPhase, DRIFT_REACQUIRE_RADIUS, recon, hop, searchFrames);
                if (Math.abs(wide - predictedPhase) > DRIFT_PHASE_RADIUS - 2
                        && (samples.length - wide) / hop >= framesThisBlock) {
                    BlockLock retry = lockBlock(samples, wide, cyclePos, hopsPerCycle, framesThisBlock,
                            searchFrames, scoreStartFrame, scoreFrameCount, pn, hop, analysisSize, numBands,
                            window, binToBand, marginLinear, pnRe, pnIm, recon);
                    if (retry.confident()) {
                        lock = retry;
                        reacquired = true;
                    }
                }
            }

            // Every block is SCORED (see lockBlock); only confident ones
            // steer where later blocks look.
            total = WatermarkDsp.ScoreDetail.combine(total, lock.heldOut());
            if (lock.confident()) {
                double observedDrift = lock.offset() - (holdoutPhase + (double) frameIndex * hop);
                if (reacquired) {
                    drift.restart(frameIndex, observedDrift);
                    blocksReacquired++;
                } else {
                    drift.add(frameIndex, observedDrift);
                }
                blocksLocked++;
            } else {
                blocksCoasted++;
            }

            frameIndex += framesThisBlock;
        }

        double weighted = total.weightedAverage();
        log.info("watermark-detect user={} drift-tracking: {} block(s) locked ({} after a jump), {} coasted, "
                        + "drift={} samp + {} samp/frame ({} ppm), weightedScore={}, detStat={}, "
                        + "simpleAvgScore={} (held-out frames={})",
                userId, blocksLocked, blocksReacquired, blocksCoasted,
                String.format("%.1f", drift.intercept()),
                String.format("%.5f", drift.slope()),
                String.format("%.0f", drift.slope() / hop * 1e6),
                String.format("%.6f", weighted),
                String.format("%.2f", total.detectionStat()),
                String.format("%.6f", total.average()), total.scoredFrames);
        return weighted;
    }

    /** One block's lock attempt: the chosen offset, its held-out score, and whether it passed the lock gate. */
    private record BlockLock(int offset, WatermarkDsp.ScoreDetail heldOut, boolean confident) {}

    /**
     * Locks one block around {@code center}. ONE FFT-based pass (masking
     * analysis + PN synthesis at center, left in {@code recon} for the
     * caller to reuse), a ±DRIFT_PHASE_RADIUS search on the block's search
     * frames, then the held-out score at the winning offset.
     *
     * The block is scored at its OWN search-portion argmax. This captures a
     * real but sub-confidence peak wherever it landed inside the search
     * window, which is the common case for a weak phone/MP3 watermark. It
     * stays unbiased because the offset was chosen on the search frames
     * while the score uses the DISJOINT held-out frames: the offset that
     * maximizes correlation on one set carries no information that inflates
     * correlation on the other, so a pure-noise block's random argmax scores
     * ~0 here rather than a spurious positive.
     *
     * Lock gate (see DRIFT_LOCK_MIN_TSTAT): only a block whose held-out
     * frames confirm its chosen offset, and whose offset isn't pinned at the
     * window edge, counts as confident. Only meaningful when the scored
     * frames really were disjoint from the searched ones
     * (scoreStartFrame > 0).
     */
    private BlockLock lockBlock(
            float[] samples, int center, long cyclePos, long hopsPerCycle, int framesThisBlock,
            int searchFrames, int scoreStartFrame, int scoreFrameCount, WatermarkDsp.PnSpectra pn,
            int hop, int analysisSize, int numBands, float[] window, int[] binToBand, double marginLinear,
            double[] pnRe, double[] pnIm, double[] recon) {

        WatermarkDsp.FrameAnalysis analysis = WatermarkDsp.analyzeAudioFrames(
                samples, center, hop, analysisSize, numBands, window, binToBand, framesThisBlock);
        WatermarkDsp.synthesizeRecon(
                analysis, pn, cyclePos, hopsPerCycle, hop, analysisSize,
                window, binToBand, marginLinear, framesThisBlock, pnRe, pnIm, recon);

        int offset = bestSearchOffset(samples, center, DRIFT_PHASE_RADIUS, recon, hop, searchFrames);
        WatermarkDsp.ScoreDetail heldOut = WatermarkDsp.correlateOnly(
                samples, offset, recon, hop, scoreStartFrame, scoreFrameCount);
        boolean interior = Math.abs(offset - center) <= DRIFT_PHASE_RADIUS - 2;
        boolean confident = scoreStartFrame > 0 && interior
                && heldOut.detectionStat() >= DRIFT_LOCK_MIN_TSTAT;
        return new BlockLock(offset, heldOut, confident);
    }

    /**
     * Slides an already-synthesized reconstruction against the recording at
     * every offset within ±radius of center, scoring ONLY the search frames
     * (pure dot-product correlation, no FFT), and returns the best offset
     * (center itself if no offset in range is usable).
     */
    private static int bestSearchOffset(
            float[] samples, int center, int radius, double[] recon, int hop, int searchFrames) {
        int bestOffset = center;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int d = -radius; d <= radius; d++) {
            int candidateOffset = center + d;
            if (candidateOffset < 0 || (samples.length - candidateOffset) / hop < searchFrames) continue;
            double score = WatermarkDsp.correlateOnly(
                    samples, candidateOffset, recon, hop, 0, searchFrames).average();
            if (score > bestScore) {
                bestScore = score;
                bestOffset = candidateOffset;
            }
        }
        return bestOffset;
    }

    /**
     * Piecewise-straight-line clock-drift model: drift(F) = intercept +
     * slope * F samples, least-squares fit through the confident blocks'
     * observed drifts since the last jump. After each new point, the point
     * furthest from the line is dropped (and the line refit) while it's more
     * than DRIFT_FIT_MAX_RESIDUAL off, so an occasional wrong lock can't bend
     * the line. After a jump (see DRIFT_REACQUIRE_RADIUS), restart() drops
     * the old segment's points, since the audio's offset moved as a block,
     * but keeps its slope, since the clocks didn't change. A segment's first
     * point fixes only the intercept; its slope needs two.
     */
    private static final class DriftFit {
        private final double maxSlope; // samples per frame
        private final List<double[]> points = new ArrayList<>(); // {F, observedDrift}
        private double intercept = 0.0;
        private double slope = 0.0;
        private double priorSlope = 0.0; // used while the current segment has fewer than two points

        DriftFit(double maxSlope) {
            this.maxSlope = maxSlope;
        }

        double predict(double frame) { return intercept + slope * frame; }
        double intercept() { return intercept; }
        double slope() { return slope; }

        void add(double frame, double observedDrift) {
            points.add(new double[] {frame, observedDrift});
            refit();
            while (points.size() > 2) {
                int worst = -1;
                double worstResidual = DRIFT_FIT_MAX_RESIDUAL;
                for (int i = 0; i < points.size(); i++) {
                    double[] p = points.get(i);
                    double residual = Math.abs(p[1] - predict(p[0]));
                    if (residual > worstResidual) {
                        worstResidual = residual;
                        worst = i;
                    }
                }
                if (worst < 0) break;
                points.remove(worst);
                refit();
            }
        }

        void restart(double frame, double observedDrift) {
            priorSlope = slope;
            points.clear();
            add(frame, observedDrift);
        }

        private void refit() {
            double meanF = 0.0, meanDrift = 0.0;
            for (double[] p : points) {
                meanF += p[0];
                meanDrift += p[1];
            }
            meanF /= points.size();
            meanDrift /= points.size();
            double sxx = 0.0, sxy = 0.0;
            for (double[] p : points) {
                sxx += (p[0] - meanF) * (p[0] - meanF);
                sxy += (p[0] - meanF) * (p[1] - meanDrift);
            }
            slope = sxx > 0.0 ? Math.max(-maxSlope, Math.min(maxSlope, sxy / sxx)) : priorSlope;
            intercept = meanDrift - slope * meanF;
        }
    }

    /** DRIFT_BLOCK_SECONDS expressed in frames, from the decoded audio's actual sample rate. */
    private int blockFramesFor(int hop, float sampleRate) {
        double framesPerSecond = sampleRate / hop;
        return Math.max(1, (int) Math.round(DRIFT_BLOCK_SECONDS * framesPerSecond));
    }
}