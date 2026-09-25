package com.convo.audio_watermark.service;

import com.convo.audio_watermark.dto.WatermarkDetectionResponse;
import com.convo.audio_watermark.repository.WatermarkConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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

    private static final Logger log = LoggerFactory.getLogger(WatermarkDetectionService.class);

    /**
     * Minimum score to declare detection.
     *
     * IMPORTANT — CALIBRATION: this score is now an ENERGY-WEIGHTED
     * aggregate over the whole recording (see WatermarkSearchEngine's
     * driftTrackedFinalScore / WatermarkDsp.ScoreDetail.weightedAverage),
     * not a plain per-frame average — the plain average was found to
     * dilute genuine signal down toward the noise floor on real-world
     * recordings with any non-trivial silent/low-SNR stretches (a
     * recording where the correct user still ranked highest, but only
     * ~0.007 vs ~0.0063 for the runner-up — both were being dragged down
     * by noise-floor frames counted with equal weight to real ones). The
     * weighted aggregate should sit noticeably higher for genuine
     * detections than the old plain average did for the same audio, but
     * this has still not been calibrated against a real corpus of
     * known-good / known-bad recordings — treat 0.015 as a placeholder
     * only, and re-run known-good/known-bad recordings through the NEW
     * scoring method (not just re-use old measurements) before relying on
     * this threshold in production.
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
    private final MeetingParticipantClient participantClient;
    private final WatermarkAudioDecoder audioDecoder;
    private final WatermarkSearchEngine searchEngine;

    public WatermarkDetectionService(
            WatermarkConfigRepository repository,
            MeetingParticipantClient participantClient,
            WatermarkAudioDecoder audioDecoder,
            WatermarkSearchEngine searchEngine) {
        this.repository = repository;
        this.participantClient = participantClient;
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

        long requestStartNanos = System.nanoTime();

        // ── 1. Fetch all registered watermark configs for this meeting ───────
        // Two independent lookups joined in Java: convo-backend owns who
        // was actually in this meeting (and their display name), this
        // service owns which of them have a watermark config. A participant
        // with no config yet (never opened a call that reached
        // getOrCreateConfig) is naturally excluded — nothing to detect
        // against for them.
        Map<String, MeetingParticipantClient.Participant> participantsByUserId = participantClient
                .listParticipants(sessionId).stream()
                .collect(Collectors.toMap(p -> p.userId().toString(), p -> p));

        List<DetectionConfigView> sessionConfigs = repository.findByMeetingCode(sessionId).stream()
                .map(cfg -> {
                    String userId = cfg.getUserId().toString();
                    MeetingParticipantClient.Participant participant = participantsByUserId.get(userId);
                    String displayName = participant != null
                            && participant.displayName() != null
                            && !participant.displayName().isBlank()
                            ? participant.displayName()
                            : userId;
                    return new DetectionConfigView(
                            userId,
                            displayName,
                            cfg.getSeed(),
                            cfg.getAlpha(),
                            cfg.getFrameSize(),
                            cfg.getAnalysisWindowSize(),
                            cfg.getNumBands(),
                            cfg.getCycleSeconds(),
                            cfg.getSampleRate());
                })
                .toList();
        if (sessionConfigs.isEmpty()) {
            return new WatermarkDetectionResponse(
                    null, null, sessionId, 0.0, false, 0, 0,
                    Collections.emptyMap(),
                    Collections.emptyMap(),
                    "No registered users found for watermark detection.");
        }

        // ── 2. Decode audio to float samples & extract sample rate ──────────
        long decodeStartNanos = System.nanoTime();
        byte[] fileBytes = audioFile.getBytes();
        String filename = audioFile.getOriginalFilename();
        WatermarkAudioDecoder.DecodedAudio decoded = audioDecoder.decode(fileBytes, filename);
        float[] samples = decoded.samples;
        float sampleRate = decoded.sampleRate;
        long decodeNanos = System.nanoTime();
        log.info("watermark-detect[{}] decode took {} ms ({} samples @ {} Hz, ~{}s audio, {} registered user(s))",
                sessionId, (decodeNanos - decodeStartNanos) / 1_000_000,
                samples.length, sampleRate,
                sampleRate > 0 ? String.format("%.1f", samples.length / sampleRate) : "?",
                sessionConfigs.size());

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

        float[] window = WatermarkDsp.hannWindow(analysisSize);

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
        for (DetectionConfigView config : sessionConfigs) {
            userDisplayNames.put(config.getUserId(), config.getDisplayName());
        }

        // ── 2b. Bring the recording to each embedder's sample rate. Every
        //    user's frame grid (hop, cycle) is defined in samples at the rate
        //    THEIR browser ran at, so a 44.1kHz embedder searched against a
        //    48kHz recording is 8.8% out of time-scale — far outside what
        //    drift tracking can absorb. Users are grouped by rate so the
        //    usual case (everyone at one rate, matching the file) costs no
        //    extra decode at all.
        List<RateGroup> rateGroups = buildRateGroups(
                sessionConfigs, decoded, fileBytes, filename, analysisSize, numBands, sessionId);

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
        long fastPathStartNanos = System.nanoTime();
        Map<String, Double> fastPathScores = searchAllRateGroups(
                rateGroups, sessionConfigs, hop, analysisSize, numBands, window, FAST_PATH_CYCLE_POS_LIMIT);
        long fastPathNanos = System.nanoTime();
        log.info("watermark-detect[{}] fast-path search took {} ms", sessionId,
                (fastPathNanos - fastPathStartNanos) / 1_000_000);
        WatermarkDetectionResponse fastPathResponse = buildDetectionResponse(
                fastPathScores, sessionConfigs, userDisplayNames, sessionId, numFrames, "near cyclePos=0 (either direction)");
        if (fastPathResponse.isWatermarkDetected()) {
            log.info("watermark-detect[{}] detected via fast path — total request time {} ms",
                    sessionId, (System.nanoTime() - requestStartNanos) / 1_000_000);
            return fastPathResponse;
        }

        // ── 3b. Fallback: full exhaustive cycle-bounded search ───────────────
        long fallbackStartNanos = System.nanoTime();
        Map<String, Double> allUserScores = searchAllRateGroups(
                rateGroups, sessionConfigs, hop, analysisSize, numBands, window, null);
        long fallbackNanos = System.nanoTime();
        log.info("watermark-detect[{}] fallback (exhaustive) search took {} ms", sessionId,
                (fallbackNanos - fallbackStartNanos) / 1_000_000);
        WatermarkDetectionResponse fallbackResponse = buildDetectionResponse(
                allUserScores, sessionConfigs, userDisplayNames, sessionId, numFrames, "full search");
        log.info("watermark-detect[{}] total request time {} ms",
                sessionId, (System.nanoTime() - requestStartNanos) / 1_000_000);
        return fallbackResponse;
    }

    /** Users whose embedders ran at one sample rate, and the recording decoded at that rate. */
    private record RateGroup(int sampleRate, float[] samples, int[] binToBand, List<DetectionConfigView> configs) {}

    private List<RateGroup> buildRateGroups(
            List<DetectionConfigView> sessionConfigs, WatermarkAudioDecoder.DecodedAudio decoded,
            byte[] fileBytes, String filename, int analysisSize, int numBands, String sessionId)
            throws IOException {

        int decodedRate = Math.round(decoded.sampleRate);
        Map<Integer, List<DetectionConfigView>> configsByRate = new LinkedHashMap<>();
        for (DetectionConfigView c : sessionConfigs) {
            // Configs issued before clients reported their rate keep the
            // old behavior: assume the recording is already at that rate.
            int rate = c.getSampleRate() != null ? c.getSampleRate() : decodedRate;
            configsByRate.computeIfAbsent(rate, r -> new ArrayList<>()).add(c);
        }
        log.info("watermark-detect[{}] embedder sample rate(s) {} Hz; recording decoded at {} Hz",
                sessionId, configsByRate.keySet(), decodedRate);

        List<RateGroup> groups = new ArrayList<>();
        for (Map.Entry<Integer, List<DetectionConfigView>> entry : configsByRate.entrySet()) {
            int rate = entry.getKey();
            float[] samples = rate == decodedRate
                    ? decoded.samples
                    : audioDecoder.decodeAtRate(fileBytes, filename, rate).samples;
            groups.add(new RateGroup(
                    rate, samples, WatermarkDsp.buildBinToBandMap(analysisSize, rate, numBands), entry.getValue()));
        }
        return groups;
    }

    /** One search pass per rate group, merged back into registration order. Scores are comparable across groups. */
    private Map<String, Double> searchAllRateGroups(
            List<RateGroup> groups, List<DetectionConfigView> sessionConfigs,
            int hop, int analysisSize, int numBands, float[] window, Long cyclePosLimit) {

        Map<String, Double> scoresByUser = new HashMap<>();
        for (RateGroup g : groups) {
            scoresByUser.putAll(searchEngine.findBestScoresAcrossUsers(
                    g.samples(), g.configs(), hop, analysisSize, numBands, g.sampleRate(),
                    window, g.binToBand(), cyclePosLimit));
        }
        Map<String, Double> ordered = new LinkedHashMap<>();
        for (DetectionConfigView c : sessionConfigs) {
            ordered.put(c.getUserId(), scoresByUser.get(c.getUserId()));
        }
        return ordered;
    }

    /**
     * Picks the best/runner-up score, applies the detection threshold +
     * margin, and builds the response DTO — shared between the fast-path
     * check and the full-search fallback so both stages apply identical
     * detection logic.
     */
    private WatermarkDetectionResponse buildDetectionResponse(
            Map<String, Double> allUserScores,
            List<DetectionConfigView> sessionConfigs,
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
        DetectionConfigView winnerConfig = sessionConfigs.stream()
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