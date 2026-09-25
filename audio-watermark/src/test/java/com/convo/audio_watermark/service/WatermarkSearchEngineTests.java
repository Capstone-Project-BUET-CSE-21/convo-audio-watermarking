package com.convo.audio_watermark.service;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WatermarkSearchEngineTests {

    private static final int RATE = 48000, HOP = 256, N = 512, BANDS = 24;

    @Test
    void lockWindowsSharingStageOneWorkScoreExactlyLikeFreshOnes() {
        // Pure noise on purpose: which alignment stage 1 picks on noise hinges on every
        // term, so any shared term that differed from a fresh one would change the result.
        float[] audio = WatermarkDspTests.colouredNoise(3 * RATE + RATE / 2, 3);
        List<DetectionConfigView> users = List.of(
                new DetectionConfigView("u1", "U1", "TEST01", 4.0, HOP, N, BANDS, 4.0, RATE));
        WatermarkSearchEngine engine = new WatermarkSearchEngine();
        try {
            // Windows at 0 and 0.5s, as WatermarkDetectionService tries them.
            WatermarkSearchEngine.Stage1Cache cache = new WatermarkSearchEngine.Stage1Cache();
            search(engine, audio, users, new WatermarkSearchEngine.LockWindow(cache, 0, RATE / 2));
            assertThat(cache.size()).isPositive();

            Map<String, WatermarkSearchEngine.UserScore> shared = search(engine, audio, users,
                    new WatermarkSearchEngine.LockWindow(cache, RATE / 2, Integer.MAX_VALUE));
            Map<String, WatermarkSearchEngine.UserScore> fresh = search(engine, audio, users,
                    new WatermarkSearchEngine.LockWindow(new WatermarkSearchEngine.Stage1Cache(), RATE / 2,
                            Integer.MAX_VALUE));
            assertThat(shared).isEqualTo(fresh);
            assertThat(cache.size()).isZero(); // nothing kept after the last window
        } finally {
            engine.shutdownExecutor();
        }
    }

    @Test
    void lockWindowStartsSnapOntoThePhaseGrid() {
        assertThat(WatermarkSearchEngine.alignToPhaseGrid(24000, HOP)).isEqualTo(24000); // 0.5s at 48kHz: already on it
        assertThat(WatermarkSearchEngine.alignToPhaseGrid(27990, HOP)).isEqualTo(27984); // 0.583s spacing
        assertThat(WatermarkSearchEngine.alignToPhaseGrid(22050, HOP)).isEqualTo(22048); // 0.5s at 44.1kHz
    }

    private static Map<String, WatermarkSearchEngine.UserScore> search(
            WatermarkSearchEngine engine, float[] audio, List<DetectionConfigView> users,
            WatermarkSearchEngine.LockWindow lockWindow) {
        float[] samples = Arrays.copyOfRange(audio, lockWindow.startSample(), audio.length);
        return engine.findBestScoresAcrossUsers(samples, users, HOP, N, BANDS, RATE, WatermarkDsp.hannWindow(N),
                WatermarkDsp.buildBinToBandMap(N, RATE, BANDS), null, null, lockWindow);
    }
}
