package com.convo.audio_watermark.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The detection decision (WatermarkDetectionService.decide), using
 * (score, consistency) pairs actually measured on real recordings.
 */
class WatermarkDecisionTests {

    private static Map<String, WatermarkSearchEngine.UserScore> scores(Object... idScoreConsistency) {
        Map<String, WatermarkSearchEngine.UserScore> m = new LinkedHashMap<>();
        for (int i = 0; i < idScoreConsistency.length; i += 3) {
            m.put((String) idScoreConsistency[i], new WatermarkSearchEngine.UserScore(
                    (double) idScoreConsistency[i + 1], (double) idScoreConsistency[i + 2]));
        }
        return m;
    }

    @Test
    void noiseThatClearedTheOldScoreThresholdIsNotDetected() {
        // A real phone recording's fast path: the old rule (score >= 0.015,
        // 0.01 ahead) named "zaki" on this.
        var d = WatermarkDetectionService.decide(scores("zaki", 0.0185, -0.90, "farya", 0.0059, -1.09));
        assertThat(d.detected()).isFalse();
        assertThat(d.strongestUserId()).isEqualTo("zaki"); // ranked by consistency: -0.90 > -1.09
    }

    @Test
    void wrongPersonOnNoiseIsNotDetected() {
        // A real phone recording of Zaki's laptop that the old rule attributed to Farya.
        var d = WatermarkDetectionService.decide(scores("zaki", 0.0072, -0.01, "farya", 0.0264, 0.84));
        assertThat(d.detected()).isFalse();
    }

    @Test
    void largeScoreWithoutConsistencyIsNotDetected() {
        // Noise reached a score of 0.060 on a real recording, four times the old threshold.
        var d = WatermarkDetectionService.decide(scores("zaki", -0.0669, -1.04, "farya", 0.0604, -0.19));
        assertThat(d.detected()).isFalse();
    }

    @Test
    void genuineWatermarkIsDetected() {
        // Real in-app recording of Zaki's laptop.
        var d = WatermarkDetectionService.decide(scores("zaki", 0.9293, 159.65, "farya", 0.0020, -1.48));
        assertThat(d.detected()).isTrue();
        assertThat(d.detectedUserIds()).containsExactly("zaki");
    }

    @Test
    void genuinePhoneRecordingIsDetected() {
        var d = WatermarkDetectionService.decide(scores("zaki", 0.1214, 18.74, "farya", -0.0214, -2.39));
        assertThat(d.detectedUserIds()).containsExactly("zaki");
    }

    @Test
    void everyoneAboveThresholdIsReportedStrongestFirst() {
        var d = WatermarkDetectionService.decide(scores("a", 0.09, 9.0, "b", 0.12, 18.7, "c", 0.01, 1.0));
        assertThat(d.detectedUserIds()).isEqualTo(List.of("b", "a"));
        assertThat(d.strongestUserId()).isEqualTo("b");
    }

    @Test
    void lockWindowsSpreadAcrossTheRecording() {
        assertThat(WatermarkDetectionService.lockWindowStarts(2.0)).containsExactly(0.0); // too short to retry
        assertThat(WatermarkDetectionService.lockWindowStarts(9.8)).containsExactly(0.0, 2.0, 4.0, 6.0);
        List<Double> longRecording = WatermarkDetectionService.lockWindowStarts(300.0);
        assertThat(longRecording).hasSize(8).startsWith(0.0);
        assertThat(longRecording.get(7)).isCloseTo(297.5, within(1e-9));
    }

    @Test
    void thresholdIsInclusive() {
        double t = WatermarkDetectionService.MIN_CONSISTENCY;
        assertThat(WatermarkDetectionService.decide(scores("a", 0.05, t)).detected()).isTrue();
        assertThat(WatermarkDetectionService.decide(scores("a", 0.05, t - 0.01)).detected()).isFalse();
    }
}
