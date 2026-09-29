package com.convo.audio_watermark.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for the real false positive in the Audio Watermarking
 * Engineering Report (§7): an in-app recording whose true alignment landed
 * at cyclePos 744 of 750 — six hops BEHIND the reset point, because audio
 * crossing the MediaStream bridge into the recorder arrives ~32ms after the
 * reset-prng message does. The old fast path only looked forward from 0,
 * missed the real recorder entirely, and the service confidently named the
 * wrong person on noise.
 *
 * Synthetic rather than the real recording (committing sample audio was
 * explicitly ruled out, and the real test needs production seeds): the
 * watermark is embedded at a chosen cyclePos with the same synthesis the
 * detector reconstructs against, into coloured noise.
 */
class WatermarkFastPathWrapTests {

    private static final int RATE = 48000, HOP = 256, N = 512, BANDS = 24;
    private static final double CYCLE_SECONDS = 4.0, ALPHA_DB = 4.0;
    private static final int HOPS_PER_CYCLE = (int) Math.round(CYCLE_SECONDS * RATE / HOP); // 750
    private static final int SECONDS = 6;

    private static final String RECORDER_SEED = "ZAKI01";
    private static final String OTHER_SEED = "FARYA1";

    private WatermarkSearchEngine engine;

    @BeforeEach
    void start() {
        engine = new WatermarkSearchEngine();
    }

    @AfterEach
    void stop() {
        engine.shutdownExecutor();
    }

    /** Coloured noise carrying RECORDER_SEED's watermark, starting at trueCyclePos. */
    private static float[] recordingAt(int trueCyclePos) {
        float[] audio = WatermarkDspTests.colouredNoise(SECONDS * RATE, 42);
        float[] window = WatermarkDsp.hannWindow(N);
        int[] binToBand = WatermarkDsp.buildBinToBandMap(N, RATE, BANDS);
        WatermarkDsp.PnSpectra pn = WatermarkDsp.buildPnSpectra(
                WatermarkDsp.hashString(RECORDER_SEED), HOPS_PER_CYCLE, N);
        int frames = (audio.length - N) / HOP;

        WatermarkDsp.FrameAnalysis host = WatermarkDsp.analyzeAudioFrames(
                audio, 0, HOP, N, BANDS, window, binToBand, frames);
        double[] watermark = new double[frames * HOP + N];
        WatermarkDsp.synthesizeRecon(host, pn, trueCyclePos, HOPS_PER_CYCLE, HOP, N, window, binToBand,
                Math.pow(10.0, ALPHA_DB / 20.0), frames, new double[N], new double[N], watermark);
        for (int i = 0; i < watermark.length && i < audio.length; i++) {
            audio[i] += (float) watermark[i];
        }
        return audio;
    }

    private Map<String, WatermarkSearchEngine.UserScore> fastPath(float[] audio) {
        List<DetectionConfigView> users = List.of(
                new DetectionConfigView("recorder", "Recorder", RECORDER_SEED, ALPHA_DB, HOP, N, BANDS, CYCLE_SECONDS, RATE),
                new DetectionConfigView("other", "Other", OTHER_SEED, ALPHA_DB, HOP, N, BANDS, CYCLE_SECONDS, RATE));
        return engine.findBestScoresAcrossUsers(audio, users, HOP, N, BANDS, RATE,
                WatermarkDsp.hannWindow(N), WatermarkDsp.buildBinToBandMap(N, RATE, BANDS),
                WatermarkDetectionService.FAST_PATH_CYCLE_POS_LIMIT, null, null);
    }

    @Test
    void fastPathFindsAlignmentSixHopsBehindZero_TheReportedCase() {
        Map<String, WatermarkSearchEngine.UserScore> scores = fastPath(recordingAt(HOPS_PER_CYCLE - 6)); // 744

        assertThat(scores.get("recorder").consistency())
                .as("true recorder at cyclePos 744 must clear the detection threshold via the fast path")
                .isGreaterThanOrEqualTo(WatermarkDetectionService.MIN_CONSISTENCY);
        assertThat(scores.get("other").consistency())
                .as("someone whose watermark isn't there must not")
                .isLessThan(WatermarkDetectionService.MIN_CONSISTENCY);
    }

    @Test
    void fastPathStillFindsAlignmentJustAfterZero() {
        Map<String, WatermarkSearchEngine.UserScore> scores = fastPath(recordingAt(6));

        assertThat(scores.get("recorder").consistency())
                .isGreaterThanOrEqualTo(WatermarkDetectionService.MIN_CONSISTENCY);
        assertThat(scores.get("other").consistency())
                .isLessThan(WatermarkDetectionService.MIN_CONSISTENCY);
    }

    @Test
    void fastPathReallyIsLimited_MidCycleAlignmentIsLeftToTheExhaustiveSearch() {
        // Control: proves the two tests above pass because the window wraps
        // around 0, not because the "fast" path quietly searches everything.
        Map<String, WatermarkSearchEngine.UserScore> scores = fastPath(recordingAt(HOPS_PER_CYCLE / 2)); // 375

        assertThat(scores.get("recorder").consistency())
                .isLessThan(WatermarkDetectionService.MIN_CONSISTENCY);
    }
}
