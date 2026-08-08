// package com.convo.audio_watermark.service;

// import java.util.Arrays;

// /**
//  * Pure signal-processing core shared by embedding-adjacent detection code —
//  * masking-threshold analysis, PN (pseudo-noise) generation, and per-frame
//  * normalised correlation scoring. Every method here is a direct, exact
//  * Java port of the front-end JS embedder (audio-processor.worklet.js) —
//  * see each method's doc for its JS counterpart. No Spring/threading
//  * concerns live here; this is pure computation, safe to call concurrently
//  * from multiple threads as long as callers don't share buffers (see
//  * WatermarkSearchEngine.ScratchBuffers).
//  */
// final class WatermarkDsp {

//     private WatermarkDsp() {}

//     /** Per-frame masking-threshold analysis of the recorded audio at a given phase. */
//     static final class FrameAnalysis {
//         final double[][] spreadThreshold; // [frame][band]
//         FrameAnalysis(double[][] spreadThreshold) {
//             this.spreadThreshold = spreadThreshold;
//         }
//     }

//     /**
//      * Jump a mulberry32 PRNG state ahead by {@code numDraws} calls in O(1).
//      *
//      * mulberry32's state update is a plain linear counter increment
//      * (state = (state + 0x6d2b79f5) mod 2^32) applied BEFORE each call's
//      * output scrambling — the scrambling never feeds back into the counter.
//      * That means the state after N calls is simply the initial state plus
//      * N * increment (mod 2^32), computable directly instead of by stepping
//      * through N calls one at a time. This is what lets the detector jump
//      * straight to a candidate cyclePos instead of replaying every prior
//      * frame in the cycle.
//      */
//     static long stateAfterDraws(long initialState, long numDraws) {
//         long increment = 0x6d2b79f5L;
//         return (initialState + numDraws * increment) & 0xFFFFFFFFL;
//     }

//     /**
//      * Analyses {@code frameCount} hop-aligned frames of the recorded audio
//      * starting at sample {@code sampleOffset} ("phase"), producing the
//      * per-frame masking threshold the embedder would have used to shape its
//      * watermark. This depends only on the recorded samples and the phase —
//      * never on which user or cyclePos is being tested — so callers compute
//      * it once per phase and reuse it across every user and every cyclePos
//      * candidate tested at that phase.
//      *
//      * WARM-UP NOTE: mirrors the embedder's zero-filled sliding analysis
//      * buffer (this._analysisBuf = new Float32Array(analysisSize) in the JS
//      * worklet), which slides left by one hop per frame — so the first frame
//      * or two analyse a window that's partly zeros, exactly as the embedder's
//      * would have been at its own startup.
//      */
//     static FrameAnalysis analyzeAudioFrames(
//             float[] samples, int sampleOffset, int hop, int analysisSize, int numBands,
//             float[] window, int[] binToBand, int frameCount) {

//         double[] analysisBuf = new double[analysisSize];
//         double[] audioRe = new double[analysisSize];
//         double[] audioIm = new double[analysisSize];
//         double[] bandEnergy = new double[numBands];
//         double[] bandLogSum = new double[numBands];
//         double[] bandCount = new double[numBands];
//         double[] bandFlatness = new double[numBands];
//         double[] bandThreshold = new double[numBands];
//         double[] spreadScratch = new double[numBands];

//         double[][] spreadThreshold = new double[frameCount][numBands];

//         for (int f = 0; f < frameCount; f++) {
//             int off = sampleOffset + f * hop;

//             System.arraycopy(analysisBuf, hop, analysisBuf, 0, analysisSize - hop);
//             int copyCount = Math.max(0, Math.min(hop, samples.length - off));
//             for (int i = 0; i < copyCount; i++) {
//                 analysisBuf[analysisSize - hop + i] = samples[off + i];
//             }
//             for (int i = copyCount; i < hop; i++) {
//                 analysisBuf[analysisSize - hop + i] = 0.0;
//             }

//             for (int i = 0; i < analysisSize; i++) {
//                 audioRe[i] = analysisBuf[i] * window[i];
//                 audioIm[i] = 0.0;
//             }
//             fft(audioRe, audioIm, false);

//             Arrays.fill(bandEnergy, 0.0);
//             Arrays.fill(bandLogSum, 0.0);
//             Arrays.fill(bandCount, 0.0);
//             for (int k = 0; k < analysisSize / 2; k++) {
//                 double mag2 = audioRe[k] * audioRe[k] + audioIm[k] * audioIm[k];
//                 int b = binToBand[k];
//                 bandEnergy[b] += mag2;
//                 bandLogSum[b] += Math.log(mag2 + 1e-12);
//                 bandCount[b] += 1.0;
//             }
//             for (int b = 0; b < numBands; b++) {
//                 double count = Math.max(bandCount[b], 1.0);
//                 double geoMean = Math.exp(bandLogSum[b] / count);
//                 double arithMean = bandEnergy[b] / count;
//                 bandFlatness[b] = geoMean / (arithMean + 1e-12);
//                 bandEnergy[b] = arithMean;
//             }
//             for (int b = 0; b < numBands; b++) {
//                 double flat = Math.min(Math.max(bandFlatness[b], 0.0), 1.0);
//                 double offsetDb = 18.0 - flat * 12.0;
//                 double energyDb = 10.0 * Math.log10(bandEnergy[b] + 1e-12);
//                 bandThreshold[b] = Math.pow(10.0, (energyDb - offsetDb) / 10.0);
//             }
//             // 3-tap left-to-right cascading spread — mirrors the JS
//             // worklet's in-place update order exactly (band b's spread uses
//             // band b-1's already-spread value and band b+1's raw value).
//             for (int b = 0; b < numBands; b++) {
//                 double left = b > 0 ? spreadScratch[b - 1] : bandThreshold[b];
//                 double right = b < numBands - 1 ? bandThreshold[b + 1] : bandThreshold[b];
//                 spreadScratch[b] = 0.5 * bandThreshold[b] + 0.25 * left + 0.25 * right;
//             }
//             System.arraycopy(spreadScratch, 0, spreadThreshold[f], 0, numBands);
//         }

//         return new FrameAnalysis(spreadThreshold);
//     }

//     /**
//      * Reconstructs the predicted watermark-only signal for one (seed,
//      * phase, cyclePos) candidate using the precomputed masking analysis,
//      * then scores it against the actual recorded audio via per-frame
//      * normalised cross-correlation, averaged across frames.
//      *
//      * CYCLE WRAP: each frame's PRNG state is derived fresh from
//      * ((cyclePos + f) mod hopsPerCycle) — mirroring the embedder's
//      * _randForFrame exactly — rather than letting one PRNG state advance
//      * continuously across the whole scoring loop. Without this, a
//      * recording that straddles a cycle boundary (its true start is near
//      * the END of a cycle and runs into the next repeat) would score
//      * correctly only up to the boundary, then compare against the wrong,
//      * "unwrapped" continuation for every frame after it — dragging the
//      * average down for no reason related to whether the seed is actually
//      * correct.
//      *
//      * Scoring is per-frame (not one global correlation) because the
//      * masking-driven shaping makes every candidate's reconstruction louder
//      * exactly when the real audio is louder, regardless of whether the seed
//      * is correct — a global correlation would pick up on that shared
//      * loudness envelope as a false signal. Normalising each frame by its own
//      * local energy isolates the actual noise-pattern match instead.
//      *
//      * @param buffers scratch space reused across every candidate evaluated
//      *                by one search task — see WatermarkSearchEngine.ScratchBuffers.
//      *                Must not be shared across concurrently-running tasks.
//      */
//     static double scoreWithAnalysis(
//             float[] samples, int sampleOffset, FrameAnalysis analysis,
//             long baseSeedState, long cyclePos, long hopsPerCycle,
//             int hop, int analysisSize, int numBands, float[] window, int[] binToBand,
//             double marginLinear, int frameCount,
//             double[] pnRe, double[] pnIm, double[] recon, long[] prngState) {

//         int reconLen = frameCount * hop + analysisSize;
//         // Only clear the portion this call actually uses — reused across
//         // every candidate, so stale values from a previous (possibly
//         // longer) call must not leak in, but there's no need to touch the
//         // rest of a buffer sized for the largest call this request makes.
//         Arrays.fill(recon, 0, reconLen, 0.0);

//         for (int f = 0; f < frameCount; f++) {
//             int off = f * hop;

//             // Fresh state per frame, wrapped to this frame's position
//             // within the cycle — matches _randForFrame in the JS embedder.
//             // Reuses prngState (mutated in place by mulberry32Next) instead
//             // of allocating a new long[] here: this runs inside the
//             // innermost loop — frames x candidates x users per request —
//             // so per-frame allocation here was a real, avoidable GC cost.
//             long cyclePosF = (cyclePos + f) % hopsPerCycle;
//             prngState[0] = stateAfterDraws(baseSeedState, cyclePosF * (long) analysisSize);

//             for (int k = 0; k < analysisSize; k++) {
//                 pnRe[k] = mulberry32Next(prngState) * 2.0 - 1.0;
//                 pnIm[k] = 0.0;
//             }
//             fft(pnRe, pnIm, false);

//             double[] spreadThreshold = analysis.spreadThreshold[f];
//             for (int k = 0; k < analysisSize / 2; k++) {
//                 int b = binToBand[k];
//                 double mag = Math.sqrt(pnRe[k] * pnRe[k] + pnIm[k] * pnIm[k]) + 1e-12;
//                 double gain = (Math.sqrt(spreadThreshold[b]) * marginLinear) / mag;
//                 pnRe[k] *= gain;
//                 pnIm[k] *= gain;
//                 int mirror = (analysisSize - k) % analysisSize;
//                 pnRe[mirror] = pnRe[k];
//                 pnIm[mirror] = -pnIm[k];
//             }

//             fft(pnRe, pnIm, true);
//             for (int i = 0; i < analysisSize; i++) {
//                 pnRe[i] *= window[i];
//             }

//             for (int i = 0; i < analysisSize && (off + i) < recon.length; i++) {
//                 recon[off + i] += pnRe[i];
//             }
//         }

//         double totalNormCorr = 0.0;
//         int scoredFrames = 0;
//         for (int f = 0; f < frameCount; f++) {
//             int off = f * hop;
//             int sOff = sampleOffset + off;
//             double corr = 0.0, audioPower = 0.0, reconPower = 0.0;
//             for (int i = 0; i < hop && (sOff + i) < samples.length; i++) {
//                 double a = samples[sOff + i];
//                 double r = recon[off + i];
//                 corr += a * r;
//                 audioPower += a * a;
//                 reconPower += r * r;
//             }
//             double denom = Math.sqrt(audioPower * reconPower);
//             if (denom > 1e-9) {
//                 totalNormCorr += corr / denom;
//                 scoredFrames++;
//             }
//         }
//         return scoredFrames > 0 ? totalNormCorr / scoredFrames : 0.0;
//     }

//     // ─────────────────────────────────────────────────────────────────────────
//     // FFT — exact port of the JS worklet's radix-2 iterative FFT
//     // ─────────────────────────────────────────────────────────────────────────

//     static void fft(double[] re, double[] im, boolean invert) {
//         int n = re.length;
//         for (int i = 1, j = 0; i < n; i++) {
//             int bit = n >> 1;
//             for (; (j & bit) != 0; bit >>= 1) {
//                 j ^= bit;
//             }
//             j ^= bit;
//             if (i < j) {
//                 double tmp = re[i]; re[i] = re[j]; re[j] = tmp;
//                 tmp = im[i]; im[i] = im[j]; im[j] = tmp;
//             }
//         }
//         for (int len = 2; len <= n; len <<= 1) {
//             double ang = (2.0 * Math.PI / len) * (invert ? -1 : 1);
//             double wr = Math.cos(ang);
//             double wi = Math.sin(ang);
//             for (int i = 0; i < n; i += len) {
//                 double curWr = 1.0, curWi = 0.0;
//                 for (int j = 0; j < len / 2; j++) {
//                     double ur = re[i + j], ui = im[i + j];
//                     double vr = re[i + j + len / 2] * curWr - im[i + j + len / 2] * curWi;
//                     double vi = re[i + j + len / 2] * curWi + im[i + j + len / 2] * curWr;
//                     re[i + j] = ur + vr;
//                     im[i + j] = ui + vi;
//                     re[i + j + len / 2] = ur - vr;
//                     im[i + j + len / 2] = ui - vi;
//                     double nWr = curWr * wr - curWi * wi;
//                     double nWi = curWr * wi + curWi * wr;
//                     curWr = nWr;
//                     curWi = nWi;
//                 }
//             }
//         }
//         if (invert) {
//             for (int i = 0; i < n; i++) {
//                 re[i] /= n;
//                 im[i] /= n;
//             }
//         }
//     }

//     // ─────────────────────────────────────────────────────────────────────────
//     // Hann window — mirrors JS _hannWindow(n)
//     // ─────────────────────────────────────────────────────────────────────────

//     static float[] hannWindow(int n) {
//         float[] w = new float[n];
//         for (int i = 0; i < n; i++) {
//             w[i] = (float) (0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (n - 1)));
//         }
//         return w;
//     }

//     // ─────────────────────────────────────────────────────────────────────────
//     // Bark-band mapping — mirrors JS _hzToBark and _buildBinToBandMap
//     // ─────────────────────────────────────────────────────────────────────────

//     static double hzToBark(double hz) {
//         return 13.0 * Math.atan(0.00076 * hz) + 3.5 * Math.atan(Math.pow(hz / 7500.0, 2));
//     }

//     static int[] buildBinToBandMap(int n, float sampleRate, int numBands) {
//         int[] map = new int[n];
//         double nyquistBark = hzToBark(sampleRate / 2.0);
//         for (int k = 0; k < n; k++) {
//             double hz = (double) k * sampleRate / n;
//             double bark = hzToBark(hz);
//             int band = (int) Math.floor((bark / nyquistBark) * numBands);
//             if (band >= numBands) band = numBands - 1;
//             if (band < 0) band = 0;
//             map[k] = band;
//         }
//         return map;
//     }

//     // ─────────────────────────────────────────────────────────────────────────
//     // PN generation — exact Java port of the front-end JS
//     // ─────────────────────────────────────────────────────────────────────────

//     /**
//      * Resolve a seed string to an unsigned 32-bit long, exactly as the JS
//      * worklet does. The JS worklet ALWAYS hashes string seeds via
//      * hashString() — there is no "numeric string -> raw integer" branch.
//      */
//     static long resolveSeed(String seed) {
//         return hashString(seed);
//     }

//     static long hashString(String str) {
//         long hash = 5381L;
//         for (int i = 0; i < str.length(); i++)
//             hash = ((((hash & 0xFFFFFFFFL) << 5) + hash) + str.charAt(i)) & 0xFFFFFFFFL;
//         return hash;
//     }

//     /** One step of mulberry32 — exact port of JS mulberry32(). */
//     static double mulberry32Next(long[] state) {
//         state[0] = (state[0] + 0x6d2b79f5L) & 0xFFFFFFFFL;
//         long s = state[0];
//         long t = imul32(s ^ (s >>> 15), 1L | s);
//         t = (t + imul32(t ^ (t >>> 7), 61L | t)) ^ t;
//         t = (t ^ (t >>> 14)) & 0xFFFFFFFFL;
//         return t / 4294967296.0;
//     }

//     static long imul32(long a, long b) {
//         return (a & 0xFFFFFFFFL) * (b & 0xFFFFFFFFL) & 0xFFFFFFFFL;
//     }
// }


package com.convo.audio_watermark.service;

import java.util.Arrays;

/**
 * Pure signal-processing core shared by embedding-adjacent detection code —
 * masking-threshold analysis, PN (pseudo-noise) generation, and per-frame
 * normalised correlation scoring. Every method here is a direct, exact
 * Java port of the front-end JS embedder (audio-processor.worklet.js) —
 * see each method's doc for its JS counterpart. No Spring/threading
 * concerns live here; this is pure computation, safe to call concurrently
 * from multiple threads as long as callers don't share buffers (see
 * WatermarkSearchEngine.ScratchBuffers).
 */
final class WatermarkDsp {

    private WatermarkDsp() {}

    /** Per-frame masking-threshold analysis of the recorded audio at a given phase. */
    static final class FrameAnalysis {
        final double[][] spreadThreshold; // [frame][band]
        FrameAnalysis(double[][] spreadThreshold) {
            this.spreadThreshold = spreadThreshold;
        }
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
    static long stateAfterDraws(long initialState, long numDraws) {
        long increment = 0x6d2b79f5L;
        return (initialState + numDraws * increment) & 0xFFFFFFFFL;
    }

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
    static FrameAnalysis analyzeAudioFrames(
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
     * Sum + count pair for one scoring call. Kept separate from the plain
     * average (see {@link #scoreWithAnalysis}) so callers that need to
     * COMBINE several scoring calls into one overall score — e.g. one call
     * per block in the drift-tracking final pass in
     * {@link WatermarkSearchEngine} — can weight each block by how many
     * frames it actually contributed, instead of averaging-of-averages
     * silently overweighting short/last blocks relative to full ones.
     */
    static final class ScoreDetail {
        final double sumNormCorr;
        final int scoredFrames;
        ScoreDetail(double sumNormCorr, int scoredFrames) {
            this.sumNormCorr = sumNormCorr;
            this.scoredFrames = scoredFrames;
        }
        double average() {
            return scoredFrames > 0 ? sumNormCorr / scoredFrames : 0.0;
        }
    }

    /**
     * Convenience wrapper around {@link #scoreWithAnalysisDetailed} for
     * every caller that only needs the average (both search stages in
     * WatermarkSearchEngine — coarse scan and local refine — since those
     * only ever compare candidates against each other, never combine
     * multiple calls into one score).
     */
    static double scoreWithAnalysis(
            float[] samples, int sampleOffset, FrameAnalysis analysis,
            long baseSeedState, long cyclePos, long hopsPerCycle,
            int hop, int analysisSize, int numBands, float[] window, int[] binToBand,
            double marginLinear, int frameCount,
            double[] pnRe, double[] pnIm, double[] recon, long[] prngState) {
        return scoreWithAnalysisDetailed(
                samples, sampleOffset, analysis, baseSeedState, cyclePos, hopsPerCycle,
                hop, analysisSize, numBands, window, binToBand, marginLinear, frameCount,
                pnRe, pnIm, recon, prngState).average();
    }

    /**
     * Reconstructs the predicted watermark-only signal for one (seed,
     * phase, cyclePos) candidate using the precomputed masking analysis,
     * then scores it against the actual recorded audio via per-frame
     * normalised cross-correlation, returning the raw sum + count instead
     * of pre-averaging (see {@link ScoreDetail}).
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
     *
     * @param buffers scratch space reused across every candidate evaluated
     *                by one search task — see WatermarkSearchEngine.ScratchBuffers.
     *                Must not be shared across concurrently-running tasks.
     */
    static ScoreDetail scoreWithAnalysisDetailed(
            float[] samples, int sampleOffset, FrameAnalysis analysis,
            long baseSeedState, long cyclePos, long hopsPerCycle,
            int hop, int analysisSize, int numBands, float[] window, int[] binToBand,
            double marginLinear, int frameCount,
            double[] pnRe, double[] pnIm, double[] recon, long[] prngState) {

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
            // Reuses prngState (mutated in place by mulberry32Next) instead
            // of allocating a new long[] here: this runs inside the
            // innermost loop — frames x candidates x users per request —
            // so per-frame allocation here was a real, avoidable GC cost.
            long cyclePosF = (cyclePos + f) % hopsPerCycle;
            prngState[0] = stateAfterDraws(baseSeedState, cyclePosF * (long) analysisSize);

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
        return new ScoreDetail(totalNormCorr, scoredFrames);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // FFT — exact port of the JS worklet's radix-2 iterative FFT
    // ─────────────────────────────────────────────────────────────────────────

    static void fft(double[] re, double[] im, boolean invert) {
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

    static float[] hannWindow(int n) {
        float[] w = new float[n];
        for (int i = 0; i < n; i++) {
            w[i] = (float) (0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (n - 1)));
        }
        return w;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Bark-band mapping — mirrors JS _hzToBark and _buildBinToBandMap
    // ─────────────────────────────────────────────────────────────────────────

    static double hzToBark(double hz) {
        return 13.0 * Math.atan(0.00076 * hz) + 3.5 * Math.atan(Math.pow(hz / 7500.0, 2));
    }

    static int[] buildBinToBandMap(int n, float sampleRate, int numBands) {
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
    // PN generation — exact Java port of the front-end JS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Resolve a seed string to an unsigned 32-bit long, exactly as the JS
     * worklet does. The JS worklet ALWAYS hashes string seeds via
     * hashString() — there is no "numeric string -> raw integer" branch.
     */
    static long resolveSeed(String seed) {
        return hashString(seed);
    }

    static long hashString(String str) {
        long hash = 5381L;
        for (int i = 0; i < str.length(); i++)
            hash = ((((hash & 0xFFFFFFFFL) << 5) + hash) + str.charAt(i)) & 0xFFFFFFFFL;
        return hash;
    }

    /** One step of mulberry32 — exact port of JS mulberry32(). */
    static double mulberry32Next(long[] state) {
        state[0] = (state[0] + 0x6d2b79f5L) & 0xFFFFFFFFL;
        long s = state[0];
        long t = imul32(s ^ (s >>> 15), 1L | s);
        t = (t + imul32(t ^ (t >>> 7), 61L | t)) ^ t;
        t = (t ^ (t >>> 14)) & 0xFFFFFFFFL;
        return t / 4294967296.0;
    }

    static long imul32(long a, long b) {
        return (a & 0xFFFFFFFFL) * (b & 0xFFFFFFFFL) & 0xFFFFFFFFL;
    }
}