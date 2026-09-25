package com.convo.audio_watermark.service;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The frequency-domain stage-1 score (WatermarkDsp.scoreSpectral) against
 * the exact time-domain score it stands in for (scoreWithAnalysis).
 */
class WatermarkDspTests {

    private static final int HOP = 256, N = 512, BANDS = 24, HOPS_PER_CYCLE = 750, FRAMES = 160;
    private static final double MARGIN = Math.pow(10.0, 4.0 / 20.0);

    @Test
    void spectralScoreRanksCandidatesLikeTheExactScore() {
        float[] window = WatermarkDsp.hannWindow(N);
        int[] binToBand = WatermarkDsp.buildBinToBandMap(N, 48000f, BANDS);
        WatermarkDsp.PnSpectra pn = WatermarkDsp.buildPnSpectra(WatermarkDsp.hashString("TEST01"), HOPS_PER_CYCLE, N);

        // Host audio: coloured noise; then hide this seed's watermark in it at cyclePos 321.
        Random rnd = new Random(7);
        float[] audio = new float[(FRAMES + 2) * HOP + N];
        double lp = 0;
        for (int i = 0; i < audio.length; i++) {
            lp = 0.9 * lp + 0.1 * rnd.nextGaussian();
            audio[i] = (float) (0.2 * lp);
        }
        int trueCyclePos = 321;
        WatermarkDsp.FrameAnalysis hostAnalysis = WatermarkDsp.analyzeAudioFrames(
                audio, 0, HOP, N, BANDS, window, binToBand, FRAMES);
        double[] watermark = new double[FRAMES * HOP + N];
        WatermarkDsp.synthesizeRecon(hostAnalysis, pn, trueCyclePos, HOPS_PER_CYCLE, HOP, N, window, binToBand,
                MARGIN, FRAMES, new double[N], new double[N], watermark);
        for (int i = 0; i < watermark.length && i < audio.length; i++) audio[i] += (float) watermark[i];

        WatermarkDsp.FrameAnalysis analysis = WatermarkDsp.analyzeAudioFrames(
                audio, 0, HOP, N, BANDS, window, binToBand, FRAMES);
        WatermarkDsp.SpectralFrames spectral = WatermarkDsp.buildSpectralFrames(
                audio, 0, analysis, FRAMES, HOP, N, window, binToBand);
        double[] pnRe = new double[N], pnIm = new double[N], recon = new double[FRAMES * HOP + N];

        double[] exact = new double[HOPS_PER_CYCLE], fast = new double[HOPS_PER_CYCLE];
        int exactBest = 0, fastBest = 0;
        for (int c = 0; c < HOPS_PER_CYCLE; c++) {
            exact[c] = WatermarkDsp.scoreWithAnalysis(audio, 0, analysis, pn, c, HOPS_PER_CYCLE, HOP, N, BANDS,
                    window, binToBand, MARGIN, FRAMES, pnRe, pnIm, recon);
            fast[c] = WatermarkDsp.scoreSpectral(spectral, pn, c, HOPS_PER_CYCLE, MARGIN);
            if (exact[c] > exact[exactBest]) exactBest = c;
            if (fast[c] > fast[fastBest]) fastBest = c;
        }

        assertThat(exactBest).isEqualTo(trueCyclePos);
        assertThat(fastBest).isEqualTo(trueCyclePos);
        assertThat(fast[trueCyclePos]).isCloseTo(exact[trueCyclePos], org.assertj.core.data.Percentage.withPercentage(10));
        assertThat(pearson(exact, fast)).isGreaterThan(0.95);
    }

    private static double pearson(double[] a, double[] b) {
        double ma = 0, mb = 0;
        for (int i = 0; i < a.length; i++) { ma += a[i]; mb += b[i]; }
        ma /= a.length; mb /= b.length;
        double sab = 0, saa = 0, sbb = 0;
        for (int i = 0; i < a.length; i++) {
            sab += (a[i] - ma) * (b[i] - mb); saa += (a[i] - ma) * (a[i] - ma); sbb += (b[i] - mb) * (b[i] - mb);
        }
        return sab / Math.sqrt(saa * sbb);
    }
}
