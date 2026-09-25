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
     * The raw (pre-gain) FFT of the PN frame at every cyclePos of ONE seed.
     *
     * A frame's PN spectrum depends only on (seed, cyclePos) — never on the
     * recorded audio, the phase being tested, or which candidate alignment
     * asked for it — so a user has exactly hopsPerCycle distinct spectra
     * (750 for a 4s cycle). The search, however, needs one per (phase,
     * cyclePos candidate, frame): millions per user in the exhaustive pass,
     * each costing 512 PRNG draws + a forward FFT. Building every distinct
     * spectrum once up front turns all of that into a table lookup, leaving
     * the inverse FFT (which DOES depend on the audio, via the masking
     * gains) as the only FFT per synthesized frame.
     *
     * Stores the raw bins rather than unit phasors so {@link #synthesizeRecon}
     * computes exactly the same floating-point operations as before
     * (raw * (amp / mag)) — the cache changes speed, not a single bit of
     * any score. The unit phasors (raw / mag) are stored as well, for
     * {@link #scoreSpectral}.
     *
     * Immutable once built; safe to share across concurrent search tasks.
     */
    static final class PnSpectra {
        final int half;      // analysisSize / 2
        final double[] re;   // [cyclePos * (half + 1) + k], k in [0, half] — includes the Nyquist bin
        final double[] im;
        final double[] mag;  // [cyclePos * half + k], k in [0, half) — |P_k| + 1e-12, the gain's denominator
        final double[] unitRe; // [cyclePos * half + k], k in [0, half) — re / mag
        final double[] unitIm;

        private PnSpectra(int half, double[] re, double[] im, double[] mag, double[] unitRe, double[] unitIm) {
            this.half = half;
            this.re = re;
            this.im = im;
            this.mag = mag;
            this.unitRe = unitRe;
            this.unitIm = unitIm;
        }
    }

    /** ~7.7MB per user for a 750-position cycle; a few ms to build (one FFT per cyclePos). */
    static PnSpectra buildPnSpectra(long baseSeedState, long hopsPerCycle, int analysisSize) {
        int half = analysisSize / 2;
        int positions = Math.toIntExact(hopsPerCycle);
        double[] re = new double[positions * (half + 1)];
        double[] im = new double[positions * (half + 1)];
        double[] mag = new double[positions * half];
        double[] unitRe = new double[positions * half];
        double[] unitIm = new double[positions * half];
        double[] pnRe = new double[analysisSize];
        double[] pnIm = new double[analysisSize];
        long[] prngState = new long[1];

        for (int cyclePos = 0; cyclePos < positions; cyclePos++) {
            // Same per-frame generator as the JS embedder's _randForFrame.
            prngState[0] = stateAfterDraws(baseSeedState, (long) cyclePos * analysisSize);
            for (int k = 0; k < analysisSize; k++) {
                pnRe[k] = mulberry32Next(prngState) * 2.0 - 1.0;
                pnIm[k] = 0.0;
            }
            fft(pnRe, pnIm, false);

            System.arraycopy(pnRe, 0, re, cyclePos * (half + 1), half + 1);
            System.arraycopy(pnIm, 0, im, cyclePos * (half + 1), half + 1);
            int magBase = cyclePos * half;
            for (int k = 0; k < half; k++) {
                double m = Math.sqrt(pnRe[k] * pnRe[k] + pnIm[k] * pnIm[k]) + 1e-12;
                mag[magBase + k] = m;
                unitRe[magBase + k] = pnRe[k] / m;
                unitIm[magBase + k] = pnIm[k] / m;
            }
        }
        return new PnSpectra(half, re, im, mag, unitRe, unitIm);
    }

    /**
     * Per-phase precomputation for {@link #scoreSpectral}: everything about
     * the recorded audio at one phase that the stage-1 score needs, shared
     * by every user and every cyclePos candidate tested at that phase.
     *
     * For frame f, the hop it's scored on (samples [phase + f*hop, +hop))
     * holds frame f's windowed FIRST half plus frame f-1's windowed SECOND
     * half (50% overlap-add). So the hop's correlation with a candidate's
     * reconstruction is <uA, x_f> + <uB, x_{f-1}>, where uA/uB are that
     * hop's audio under the corresponding half of the synthesis window,
     * placed where it sits within each frame, and x is a frame's
     * synthesized signal. By Parseval each inner product is a dot product
     * of spectra, and a candidate's frame spectrum is just its unit PN
     * phasors times the masking amplitudes sqrt(threshold) * margin — so
     * the audio-side factors (spectrum of uA/uB times the amplitudes) are
     * computed once here, leaving a 256-bin dot product per candidate per
     * frame instead of synthesizing and inverse-transforming the frame.
     */
    static final class SpectralFrames {
        final int frames, half, n;
        final double[] aRe, aIm;   // [f * half + k]: spectrum of uA_f * sqrt(thr_f) * (1 or 2) / n
        final double[] bRe, bIm;   // [f * half + k]: spectrum of uB_f * sqrt(thr_{f-1}) * (1 or 2) / n (f >= 1)
        final double[] aNyquist;   // [f]: uA_f's Nyquist bin / n (multiplies the raw, unshaped PN Nyquist bin)
        final double[] bNyquist;
        final double[] audioPower; // [f]: sum of the hop's squared samples
        final double[] shapedPower; // [f]: sum over all n bins of threshold_f (a frame's shaped power, before margin)
        final double windowFirstHalfEnergy, windowSecondHalfEnergy;

        private SpectralFrames(int frames, int half, int n, double[] aRe, double[] aIm, double[] bRe, double[] bIm,
                               double[] aNyquist, double[] bNyquist, double[] audioPower, double[] shapedPower,
                               double windowFirstHalfEnergy, double windowSecondHalfEnergy) {
            this.frames = frames;
            this.half = half;
            this.n = n;
            this.aRe = aRe;
            this.aIm = aIm;
            this.bRe = bRe;
            this.bIm = bIm;
            this.aNyquist = aNyquist;
            this.bNyquist = bNyquist;
            this.audioPower = audioPower;
            this.shapedPower = shapedPower;
            this.windowFirstHalfEnergy = windowFirstHalfEnergy;
            this.windowSecondHalfEnergy = windowSecondHalfEnergy;
        }
    }

    /** Builds {@link SpectralFrames} for one phase. Requires n == 2 * hop (the embedder's 50% overlap). */
    static SpectralFrames buildSpectralFrames(float[] samples, int sampleOffset, FrameAnalysis analysis,
                                              int frameCount, int hop, int n, float[] window, int[] binToBand) {
        int half = n / 2;
        double[] uaRe = new double[n], uaIm = new double[n], ubRe = new double[n], ubIm = new double[n];
        double[] aRe = new double[frameCount * half], aIm = new double[frameCount * half];
        double[] bRe = new double[frameCount * half], bIm = new double[frameCount * half];
        double[] aNyquist = new double[frameCount], bNyquist = new double[frameCount];
        double[] audioPower = new double[frameCount], shapedPower = new double[frameCount];
        double[] amp = new double[half], prevAmp = new double[half];

        double w1 = 0.0, w2 = 0.0;
        for (int i = 0; i < hop; i++) {
            w1 += (double) window[i] * window[i];
            w2 += (double) window[hop + i] * window[hop + i];
        }

        for (int f = 0; f < frameCount; f++) {
            int start = sampleOffset + f * hop;
            Arrays.fill(uaRe, 0.0);
            Arrays.fill(uaIm, 0.0);
            Arrays.fill(ubRe, 0.0);
            Arrays.fill(ubIm, 0.0);
            double power = 0.0;
            for (int i = 0; i < hop && start + i < samples.length; i++) {
                double v = samples[start + i];
                uaRe[i] = v * window[i];             // under frame f's first half
                ubRe[hop + i] = v * window[hop + i]; // under frame f-1's second half
                power += v * v;
            }
            audioPower[f] = power;
            fft(uaRe, uaIm, false);
            fft(ubRe, ubIm, false);

            double[] threshold = analysis.spreadThreshold[f];
            double shaped = 0.0;
            for (int k = 0; k < half; k++) {
                amp[k] = Math.sqrt(threshold[binToBand[k]]);
                // Real signals: bins 1..half-1 stand for themselves AND their
                // mirror, DC only for itself.
                double weight = (k == 0 ? 1.0 : 2.0) / n;
                aRe[f * half + k] = uaRe[k] * amp[k] * weight;
                aIm[f * half + k] = uaIm[k] * amp[k] * weight;
                if (f > 0) {
                    bRe[f * half + k] = ubRe[k] * prevAmp[k] * weight;
                    bIm[f * half + k] = ubIm[k] * prevAmp[k] * weight;
                }
                shaped += (k == 0 ? 1.0 : 2.0) * amp[k] * amp[k];
            }
            aNyquist[f] = uaRe[half] / n;
            bNyquist[f] = ubRe[half] / n;
            shapedPower[f] = shaped;
            double[] t = prevAmp;
            prevAmp = amp;
            amp = t;
        }
        return new SpectralFrames(frameCount, half, n, aRe, aIm, bRe, bIm, aNyquist, bNyquist,
                audioPower, shapedPower, w1, w2);
    }

    /**
     * Stage-1 candidate score computed in the frequency domain — the same
     * average of per-hop normalized correlations as {@link #scoreWithAnalysis},
     * roughly 15x cheaper. The correlation itself is EXACT (Parseval, see
     * {@link SpectralFrames}). The one approximation is the reconstruction's
     * power per hop, which the normalization divides by: it depends on the
     * candidate's PN phases only through chance, so it's replaced by its
     * expected value over random phases, which is the same for every
     * candidate apart from the unshaped Nyquist bin (handled exactly). That
     * perturbs individual scores by a few percent, which is fine for the
     * one job this is used for — ranking thousands of candidates to find
     * the one worth refining. Stage 2 re-scores the neighbourhood of the
     * winner exactly, and stage 3 computes the reported score exactly.
     */
    static double scoreSpectral(SpectralFrames sf, PnSpectra pn, long cyclePos, long hopsPerCycle, double marginLinear) {
        int half = sf.half;
        double nSquared = (double) sf.n * sf.n;
        double total = 0.0;
        int scored = 0;
        double prevVariance = 0.0;
        for (int f = 0; f < sf.frames; f++) {
            int cp = (int) ((cyclePos + f) % hopsPerCycle);
            int unitBase = cp * half, frameBase = f * half;
            double shapedDot = 0.0;
            for (int k = 0; k < half; k++) {
                shapedDot += sf.aRe[frameBase + k] * pn.unitRe[unitBase + k]
                        + sf.aIm[frameBase + k] * pn.unitIm[unitBase + k];
            }
            double nyquist = pn.re[cp * (half + 1) + half];
            double corr = marginLinear * shapedDot + sf.aNyquist[f] * nyquist;
            // Expected per-sample power of this frame's synthesized signal over random PN phases.
            double variance = (marginLinear * marginLinear * sf.shapedPower[f] + nyquist * nyquist) / nSquared;
            double reconPower = sf.windowFirstHalfEnergy * variance;
            if (f > 0) {
                int cpPrev = (int) ((cyclePos + f - 1) % hopsPerCycle);
                int prevBase = cpPrev * half;
                double prevDot = 0.0;
                for (int k = 0; k < half; k++) {
                    prevDot += sf.bRe[frameBase + k] * pn.unitRe[prevBase + k]
                            + sf.bIm[frameBase + k] * pn.unitIm[prevBase + k];
                }
                corr += marginLinear * prevDot + sf.bNyquist[f] * pn.re[cpPrev * (half + 1) + half];
                reconPower += sf.windowSecondHalfEnergy * prevVariance;
            }
            double denom = Math.sqrt(sf.audioPower[f] * reconPower);
            if (denom > 1e-9) {
                total += corr / denom;
                scored++;
            }
            prevVariance = variance;
        }
        return scored > 0 ? total / scored : 0.0;
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
     * Sum + count pair for one scoring call, PLUS the raw (unnormalized)
     * pieces needed to combine many calls into one energy-weighted
     * aggregate instead of a plain average — see {@link #weightedAverage()}
     * and WatermarkSearchEngine's drift-tracking final pass, which uses
     * this to avoid diluting genuine signal with low-energy (silence /
     * background-noise) segments of a real-world recording.
     */
    static final class ScoreDetail {
        final double sumNormCorr;
        final int scoredFrames;
        /** Sum of each scored frame's RAW (unnormalized) correlation. */
        final double sumRawCorr;
        /** Sum of each scored frame's sqrt(audioPower*reconPower) — the
         *  same quantity each frame's correlation is normalised BY, reused
         *  here as a per-frame energy/confidence weight. */
        final double sumDenom;
        /** Sum of each scored frame's SQUARED normalised correlation —
         *  enables a duration-STABLE detection diagnostic (see
         *  {@link #detectionStat()}): with the plain average, a longer
         *  recording drives a wrong user's score toward 0 while a real
         *  user's stays put, but the two scales aren't directly
         *  comparable across durations; a standardized statistic is. */
        final double sumSqNormCorr;

        ScoreDetail(double sumNormCorr, int scoredFrames, double sumRawCorr,
                    double sumDenom, double sumSqNormCorr) {
            this.sumNormCorr = sumNormCorr;
            this.scoredFrames = scoredFrames;
            this.sumRawCorr = sumRawCorr;
            this.sumDenom = sumDenom;
            this.sumSqNormCorr = sumSqNormCorr;
        }

        /** Plain per-frame average of the normalised correlation coefficient — unchanged, original behavior. */
        double average() {
            return scoredFrames > 0 ? sumNormCorr / scoredFrames : 0.0;
        }

        /**
         * Standardized detection statistic: mean(c) * sqrt(n) / std(c) over
         * the scored per-frame normalised correlations c. Under H0 (wrong
         * seed) this is ~N(0,1) REGARDLESS of recording length, so it's
         * directly comparable across 2s and 5s and across users; under H1
         * (real, consistently-aligned watermark) it GROWS ~sqrt(n) with
         * duration. This is what the detection decision is made on (see
         * WatermarkDetectionService.MIN_CONSISTENCY): on real recordings it
         * separated genuine watermarks from noise cleanly, where the
         * weightedAverage magnitude did not.
         */
        double detectionStat() {
            if (scoredFrames < 2) return 0.0;
            double mean = sumNormCorr / scoredFrames;
            double var = (sumSqNormCorr / scoredFrames) - mean * mean;
            if (var <= 1e-18) return 0.0;
            return mean * Math.sqrt(scoredFrames) / Math.sqrt(var);
        }

        /**
         * Energy-weighted aggregate: sum(rawCorr) / sum(denom), i.e. every
         * frame's (already scale-invariant, bias-free) correlation
         * coefficient re-weighted by that frame's OWN signal energy before
         * combining, instead of counting a near-silent/noisy frame's
         * high-variance coefficient exactly as much as a loud,
         * watermark-carrying frame's low-variance one. Still bounded in
         * [-1, 1] (a weighted average of per-frame values each individually
         * bounded there by Cauchy-Schwarz), and the weight (denom) is
         * essentially the SAME for every candidate seed tested at a given
         * position — it comes from the shared masking-threshold analysis
         * and the recorded audio, not from the seed — so this doesn't
         * introduce any seed-dependent bias between competing candidates,
         * it just stops quiet/noisy stretches of a real recording from
         * diluting genuine signal down toward zero.
         *
         * NOTE: this is duration-stable ONLY as long as every scored frame
         * is genuinely aligned (carries the repeating watermark). The whole
         * point of the drift-slope tracking in
         * WatermarkSearchEngine.driftTrackedFinalScore is to keep that
         * true — if later frames were allowed to fall out of alignment (as
         * they did before that fix), their large-energy / zero-correlation
         * contributions would land in sumDenom with nothing in sumRawCorr
         * and drag this toward 0 as the recording got longer. Alignment,
         * not the combiner, is what makes longer recordings safe.
         */
        double weightedAverage() {
            return sumDenom > 1e-9 ? sumRawCorr / sumDenom : 0.0;
        }

        /** Two ScoreDetail results combined (e.g. across blocks) into one. */
        static ScoreDetail combine(ScoreDetail a, ScoreDetail b) {
            return new ScoreDetail(
                    a.sumNormCorr + b.sumNormCorr,
                    a.scoredFrames + b.scoredFrames,
                    a.sumRawCorr + b.sumRawCorr,
                    a.sumDenom + b.sumDenom,
                    a.sumSqNormCorr + b.sumSqNormCorr);
        }

        static ScoreDetail zero() {
            return new ScoreDetail(0.0, 0, 0.0, 0.0, 0.0);
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
            PnSpectra pn, long cyclePos, long hopsPerCycle,
            int hop, int analysisSize, int numBands, float[] window, int[] binToBand,
            double marginLinear, int frameCount,
            double[] pnRe, double[] pnIm, double[] recon) {
        return scoreWithAnalysisDetailed(
                samples, sampleOffset, analysis, pn, cyclePos, hopsPerCycle,
                hop, analysisSize, numBands, window, binToBand, marginLinear, frameCount,
                pnRe, pnIm, recon).average();
    }

    /**
     * Reconstructs the predicted watermark-only signal for one (seed,
     * phase, cyclePos) candidate using the precomputed masking analysis,
     * then scores it against the actual recorded audio via per-frame
     * normalised cross-correlation, returning the raw sum + count instead
     * of pre-averaging (see {@link ScoreDetail}).
     *
     * CYCLE WRAP: each frame's PN spectrum is looked up fresh from
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
            PnSpectra pn, long cyclePos, long hopsPerCycle,
            int hop, int analysisSize, int numBands, float[] window, int[] binToBand,
            double marginLinear, int frameCount,
            double[] pnRe, double[] pnIm, double[] recon) {

        synthesizeRecon(analysis, pn, cyclePos, hopsPerCycle, hop, analysisSize,
                window, binToBand, marginLinear, frameCount, pnRe, pnIm, recon);
        return correlateOnly(samples, sampleOffset, recon, hop, 0, frameCount);
    }

    /**
     * The FFT-bearing half of {@link #scoreWithAnalysisDetailed}: builds
     * the predicted watermark-only time-domain signal for one (seed,
     * cyclePos) candidate into {@code recon}, WITHOUT correlating it
     * against any recorded audio yet. Split out from the correlation step
     * (see {@link #correlateOnly}) so a caller that wants to test several
     * nearby sample-offset alignments against the SAME reconstruction —
     * e.g. WatermarkSearchEngine's drift-tracking block search — can do
     * so by calling this ONCE per block and correlateOnly() cheaply per
     * candidate offset, instead of paying the FFT cost of this method
     * again for every offset tried. That reuse is the entire point: the
     * reconstruction depends on the masking analysis, the seed, and
     * cyclePos — never on which sample offset we're about to test it
     * against — so recomputing it per offset was pure waste.
     *
     * CYCLE WRAP: see {@link #scoreWithAnalysisDetailed} doc (unchanged
     * behavior, just relocated).
     */
    static void synthesizeRecon(
            FrameAnalysis analysis, PnSpectra pn, long cyclePos, long hopsPerCycle,
            int hop, int analysisSize, float[] window, int[] binToBand,
            double marginLinear, int frameCount,
            double[] pnRe, double[] pnIm, double[] recon) {

        int reconLen = frameCount * hop + analysisSize;
        // Only clear the portion this call actually uses — reused across
        // every block/candidate, so stale values from a previous (possibly
        // longer) call must not leak in, but there's no need to touch the
        // rest of a buffer sized for the largest call this request makes.
        Arrays.fill(recon, 0, reconLen, 0.0);

        int half = pn.half;
        for (int f = 0; f < frameCount; f++) {
            int off = f * hop;

            // This frame's position within the cycle — matches
            // _randForFrame in the JS embedder — selects its precomputed
            // raw PN spectrum (see PnSpectra).
            int cyclePosF = (int) ((cyclePos + f) % hopsPerCycle);
            int binBase = cyclePosF * (half + 1);
            int magBase = cyclePosF * half;

            double[] spreadThreshold = analysis.spreadThreshold[f];
            for (int k = 0; k < half; k++) {
                int b = binToBand[k];
                double gain = (Math.sqrt(spreadThreshold[b]) * marginLinear) / pn.mag[magBase + k];
                double re = pn.re[binBase + k] * gain;
                double im = pn.im[binBase + k] * gain;
                pnRe[k] = re;
                pnIm[k] = im;
                int mirror = (analysisSize - k) % analysisSize;
                pnRe[mirror] = re;
                pnIm[mirror] = -im;
            }
            // The embedder's gain loop stops at k < N/2, so the Nyquist bin
            // keeps its RAW, unshaped PN value (an audio-independent
            // component at fs/2). Reproduce that exactly.
            pnRe[half] = pn.re[binBase + half];
            pnIm[half] = pn.im[binBase + half];

            fft(pnRe, pnIm, true);
            for (int i = 0; i < analysisSize; i++) {
                pnRe[i] *= window[i];
            }

            for (int i = 0; i < analysisSize && (off + i) < recon.length; i++) {
                recon[off + i] += pnRe[i];
            }
        }
    }

    /**
     * The correlation-only half of {@link #scoreWithAnalysisDetailed}:
     * scores an ALREADY-SYNTHESIZED reconstruction (see
     * {@link #synthesizeRecon}) against the recorded audio via per-frame
     * normalised cross-correlation. Pure arithmetic — no FFT, no PRNG
     * draws — so this is cheap enough to call once per candidate offset in
     * a local phase search (see WatermarkSearchEngine's drift tracking)
     * without that search ever becoming the bottleneck.
     *
     * @param startFrame scores frames [startFrame, startFrame+frameCount)
     *                   of the reconstruction/recording rather than always
     *                   starting at frame 0. THIS IS NOT A CONVENIENCE
     *                   PARAMETER — it exists so a caller can evaluate a
     *                   DIFFERENT slice of audio than whichever slice it
     *                   used to CHOOSE {@code sampleOffset} via an argmax
     *                   search. Scoring the very data a search maximized
     *                   over is a textbook selection-bias trap: with many
     *                   candidate offsets tried, SOME candidate is
     *                   guaranteed to correlate unusually well with THAT
     *                   SPECIFIC audio purely by chance, for ANY seed —
     *                   correct or not. That inflated, chance-driven
     *                   correlation is real (it's not a bug in the math),
     *                   but it says nothing about whether the seed is
     *                   genuinely embedded — it would appear for a
     *                   completely unrelated seed too, just for a
     *                   different, equally-arbitrary offset. Evaluating a
     *                   held-out slice the search never touched removes
     *                   that bias entirely: whatever correlation shows up
     *                   there reflects the actual audio content, not the
     *                   search's own ability to find a good-looking
     *                   coincidence. See WatermarkSearchEngine's
     *                   driftTrackedFinalScore for how search and scoring
     *                   are kept on disjoint frames throughout.
     *
     * Scoring is per-frame (not one global correlation) because the
     * masking-driven shaping makes every candidate's reconstruction louder
     * exactly when the real audio is louder, regardless of whether the seed
     * is correct — a global correlation would pick up on that shared
     * loudness envelope as a false signal. Normalising each frame by its own
     * local energy isolates the actual noise-pattern match instead.
     */
    static ScoreDetail correlateOnly(
            float[] samples, int sampleOffset, double[] recon, int hop, int startFrame, int frameCount) {

        double totalNormCorr = 0.0;
        double totalRawCorr = 0.0;
        double totalDenom = 0.0;
        double totalSqNormCorr = 0.0;
        int scoredFrames = 0;
        for (int fi = 0; fi < frameCount; fi++) {
            int f = startFrame + fi;
            int off = f * hop;
            int sOff = sampleOffset + off;
            double corr = 0.0, audioPower = 0.0, reconPower = 0.0;
            for (int i = 0; i < hop && (sOff + i) < samples.length && (off + i) < recon.length; i++) {
                double a = samples[sOff + i];
                double r = recon[off + i];
                corr += a * r;
                audioPower += a * a;
                reconPower += r * r;
            }
            double denom = Math.sqrt(audioPower * reconPower);
            if (denom > 1e-9) {
                double c = corr / denom;
                totalNormCorr += c;
                totalRawCorr += corr;
                totalDenom += denom;
                totalSqNormCorr += c * c;
                scoredFrames++;
            }
        }
        return new ScoreDetail(totalNormCorr, scoredFrames, totalRawCorr, totalDenom, totalSqNormCorr);
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