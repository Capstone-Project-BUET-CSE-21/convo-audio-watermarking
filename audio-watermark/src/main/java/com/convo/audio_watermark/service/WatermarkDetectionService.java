package com.convo.audio_watermark.service;

import com.convo.audio_watermark.dto.WatermarkDetectionResponse;
import com.convo.audio_watermark.repository.WatermarkConfigRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Detects audio watermarks embedded by the front-end audio worklet.
 *
 * Orchestration only — the actual work is split across three collaborators:
 * <ul>
 *   <li>{@link WatermarkAudioDecoder} — turns an uploaded file into mono
 *       float PCM samples (WAV natively, everything else via ffmpeg).</li>
 *   <li>{@link WatermarkSearchEngine} — the cycle-bounded synchronization
 *       search (coarse scan, local refine, final scoring) that pinpoints
 *       each registered user's alignment and score. See its class doc for
 *       why cyclePos must be scanned exhaustively.</li>
 *   <li>{@link WatermarkDsp} — the pure signal-processing core (FFT,
 *       masking-threshold analysis, PN generation, correlation scoring)
 *       both of the above build on, mirroring the JS embedder exactly.</li>
 * </ul>
 */
@Service
public class WatermarkDetectionService {

    /**
     * Minimum average per-frame normalised correlation to declare detection.
     *
     * IMPORTANT — CALIBRATION: this score is the average of PER-FRAME
     * normalised correlations at the winning alignment (see
     * WatermarkDsp.scoreWithAnalysis), not a single whole-recording
     * correlation. This has not yet been calibrated against real
     * known-good / known-bad recordings. Treat 0.015 below as a placeholder
     * only — re-run known-good and known-bad test recordings with this
     * scoring method and set both thresholds from that data before relying
     * on this in production.
     */
    private static final double DETECTION_THRESHOLD = 0.015;

    /**
     * Minimum gap between the best and second-best score required to trust
     * the winner. Without this, two close/noisy scores can flip the
     * "detected" user essentially at random. Needs the same fresh
     * calibration as DETECTION_THRESHOLD.
     */
    private static final double MIN_SCORE_MARGIN = 0.01;

    /**
     * Width, in EITHER direction, of the cheap fast-path window around
     * cyclePos=0 tried before falling back to the full exhaustive search
     * (see detect() stage 3a; the actual wraparound handling lives in
     * WatermarkSearchEngine.findBestScoresAcrossUsers). In-app recordings
     * reset the embedder's cycle position to 0 the instant recording starts
     * (useMeetingRecording.js's 'reset-prng' message), so their true
     * cyclePos should be extremely close to 0 — but not necessarily AT or
     * AFTER it. Confirmed against a real recording: the reset message
     * itself was delivered promptly (~7ms), yet the true alignment still
     * landed 6 hops on the OTHER side of the wrap (hopsPerCycle - 6), most
     * likely from audio-sample latency in the cross-AudioContext
     * MediaStream bridge between the embedder and the recorder — a
     * separate thing from message-delivery latency, and not something a
     * forward-only window can ever catch. 50 hops = 50 * 256 / 48000 ≈
     * 267ms of tolerance in each direction, comfortably more than the
     * observed ~32ms (6-hop) gap.
     */
    private static final long FAST_PATH_CYCLE_POS_LIMIT = 50;

    private final WatermarkConfigRepository repository;
    private final WatermarkAudioDecoder audioDecoder;
    private final WatermarkSearchEngine searchEngine;

    public WatermarkDetectionService(
            WatermarkConfigRepository repository,
            WatermarkAudioDecoder audioDecoder,
            WatermarkSearchEngine searchEngine) {
        this.repository = repository;
        this.audioDecoder = audioDecoder;
        this.searchEngine = searchEngine;
    }

    /**
     * Convenience for manual/test callers that construct this service
     * directly (bypassing Spring, so no @PreDestroy lifecycle runs) — the
     * search engine owns the actual thread pool.
     */
    public void shutdownExecutor() {
        searchEngine.shutdownExecutor();
    }

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
        WatermarkAudioDecoder.DecodedAudio decoded = audioDecoder.decode(audioFile);
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
        float[] window = WatermarkDsp.hannWindow(analysisSize);
        int[] binToBand = WatermarkDsp.buildBinToBandMap(analysisSize, sampleRate, numBands);

        int hop = frameSize;
        int numFrames = samples.length / hop;

        if (numFrames == 0) {
            return new WatermarkDetectionResponse(
                    null, null, sessionId, 0.0, false, 0, sessionConfigs.size(),
                    Collections.emptyMap(),
                    Collections.emptyMap(),
                    "Audio too short for detection (need at least " + hop + " samples).");
        }

        Map<String, String> userDisplayNames = new LinkedHashMap<>();
        for (WatermarkConfigRepository.DetectionConfigProjection config : sessionConfigs) {
            userDisplayNames.put(config.getUserId(), config.getDisplayName());
        }

        // ── 3a. Fast pass: try a small, cheap search near cyclePos=0 first,
        //    in BOTH directions (see WatermarkSearchEngine's cyclePosLimit
        //    doc for why the backward/wraparound side matters just as much
        //    as the forward side). The in-app recorder resets the
        //    embedder's cycle position to 0 the instant recording starts
        //    (see useMeetingRecording.js's 'reset-prng' message and the
        //    worklet's handler for it), so for those recordings the true
        //    alignment is at or very near cyclePos=0 — checking there costs
        //    almost nothing compared to the full exhaustive scan. External/
        //    uncooperative recordings never get that reset, so this pass
        //    naturally won't find a confident match for them, and falls
        //    through to the full search below — this is what keeps the
        //    exhaustive search's generality for EXTERNAL recorders while
        //    giving in-app ones a near-instant path.
        Map<String, Double> fastPathScores = searchEngine.findBestScoresAcrossUsers(
                samples, sessionConfigs, hop, analysisSize, numBands, sampleRate, window, binToBand,
                FAST_PATH_CYCLE_POS_LIMIT);
        WatermarkDetectionResponse fastPathResponse = buildDetectionResponse(
                fastPathScores, sessionConfigs, userDisplayNames, sessionId, numFrames, "near cyclePos=0 (either direction)");
        if (fastPathResponse.isWatermarkDetected()) {
            return fastPathResponse;
        }

        // ── 3b. Fallback: full exhaustive cycle-bounded search ───────────────
        Map<String, Double> allUserScores = searchEngine.findBestScoresAcrossUsers(
                samples, sessionConfigs, hop, analysisSize, numBands, sampleRate, window, binToBand, null);
        return buildDetectionResponse(
                allUserScores, sessionConfigs, userDisplayNames, sessionId, numFrames, "full search");
    }

    /**
     * Picks the best/runner-up score, applies the detection threshold +
     * margin, and builds the response DTO — shared between the fast-path
     * check and the full-search fallback so both stages apply identical
     * detection logic.
     */
    private WatermarkDetectionResponse buildDetectionResponse(
            Map<String, Double> allUserScores,
            List<WatermarkConfigRepository.DetectionConfigProjection> sessionConfigs,
            Map<String, String> userDisplayNames,
            String sessionId, int numFrames, String pathLabel) {

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
            secondBestScore = bestScore;
        }

        // The margin check only makes sense when there's a runner-up to be
        // confused with. With exactly one registered user, secondBestScore
        // is set equal to bestScore just above (no real runner-up exists),
        // which would make the margin exactly 0 and detection permanently
        // impossible regardless of how strong the real score is — skip the
        // margin requirement in that case and rely on the raw threshold.
        boolean detected = bestScore >= DETECTION_THRESHOLD
                && (allUserScores.size() == 1 || (bestScore - secondBestScore) >= MIN_SCORE_MARGIN);

        final String finalBestUser = bestUserId;
        WatermarkConfigRepository.DetectionConfigProjection winnerConfig = sessionConfigs.stream()
                .filter(c -> c.getUserId().equals(finalBestUser))
                .findFirst()
                .orElse(sessionConfigs.get(0));

        String message = detected
                ? String.format(
                        "Watermark detected (%s). Detected user: '%s' | score=%.6f (margin over runner-up=%.6f)",
                        pathLabel, winnerConfig.getDisplayName(), bestScore, bestScore - secondBestScore)
                : String.format(
                        "No watermark detected (%s). Highest score: %.6f for user '%s' (margin over runner-up=%.6f)",
                        pathLabel, bestScore, winnerConfig.getDisplayName(), bestScore - secondBestScore);

        Map<String, Double> roundedScores = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : allUserScores.entrySet()) {
            roundedScores.put(e.getKey(), round4(e.getValue()));
        }

        return new WatermarkDetectionResponse(
                detected ? winnerConfig.getUserId() : null,
                detected ? winnerConfig.getDisplayName() : null,
                sessionId,
                round4(bestScore),
                detected,
                numFrames,
                sessionConfigs.size(),
                roundedScores,
                userDisplayNames,
                message);
    }

    private double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
