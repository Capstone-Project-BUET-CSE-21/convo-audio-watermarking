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
import java.util.Arrays;
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
     * Minimum consistency for a user to count as detected: the
     * standardized statistic over the held-out frames
     * (WatermarkDsp.ScoreDetail.detectionStat — mean per-frame correlation
     * relative to its own frame-to-frame spread, scaled by sqrt(frames)).
     *
     * Decided on this rather than on the weighted score, because on real
     * recordings the score's magnitude couldn't tell signal from noise:
     * pure noise reached 0.060 while a genuine phone recording scored
     * 0.06–0.14, and the old rule (score >= 0.015 and 0.01 ahead of the
     * runner-up) named participants on noise — once the wrong one.
     * Measured 2026-09-25 across synthetic tests and six real recordings:
     *   genuine watermark:  11.6–18.7 on a real phone recording, 160 on a
     *                       real in-app recording, 56–160 synthetic
     *   wrong user / none:  never above 3.9 (~70 measurements)
     * 6.0 leaves margin on both sides. Still a small real-world sample —
     * revisit as more recordings with known answers come in.
     */
    static final double MIN_CONSISTENCY = 6.0;

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

    /**
     * FULL-SEARCH LOCK WINDOWS. The alignment search only looks at
     * SEARCH_FRAME_COUNT frames (~0.85s) at a time, and on real phone
     * recordings whether a given stretch works is unpredictable: one locked
     * from 1, 3, 7, 9 and 11s but not from 0s, another only from 10s. So
     * when the search from the start finds nothing, it's retried from later
     * points, stopping at the first that yields a detection. Windows are
     * spread evenly across the recording, at least
     * LOCK_WINDOW_MIN_SPACING_SECONDS apart, and each must leave
     * LOCK_WINDOW_MIN_REMAINING_SECONDS after it to score. Every window
     * costs a full exhaustive search, hence the cap — and trying more
     * windows also gives noise more chances, which MIN_CONSISTENCY's margin
     * (noise never above 3.9) absorbs at this count.
     */
    private static final int MAX_LOCK_WINDOWS = 8;
    private static final double LOCK_WINDOW_MIN_SPACING_SECONDS = 2.0;
    private static final double LOCK_WINDOW_MIN_REMAINING_SECONDS = 2.5;

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
                    "No registered users found for watermark detection.",
                    0.0, Collections.emptyMap());
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
                    "Audio file is empty or could not be decoded.",
                    0.0, Collections.emptyMap());
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
                    "Audio too short for detection (need at least " + hop + " samples).",
                    0.0, Collections.emptyMap());
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
        Map<String, WatermarkSearchEngine.UserScore> fastPathScores = searchAllRateGroups(
                rateGroups, sessionConfigs, hop, analysisSize, numBands, window, FAST_PATH_CYCLE_POS_LIMIT, 0.0);
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

        // ── 3b. Fallback: full exhaustive cycle-bounded search, locking from
        //    several windows (see MAX_LOCK_WINDOWS) and stopping at the first
        //    detection. If none detects, report the window whose strongest
        //    user came closest.
        WatermarkDetectionResponse closest = null;
        for (double startSeconds : lockWindowStarts(samples.length / sampleRate)) {
            long windowStartNanos = System.nanoTime();
            Map<String, WatermarkSearchEngine.UserScore> scores = searchAllRateGroups(
                    rateGroups, sessionConfigs, hop, analysisSize, numBands, window, null, startSeconds);
            WatermarkDetectionResponse response = buildDetectionResponse(
                    scores, sessionConfigs, userDisplayNames, sessionId, numFrames,
                    String.format("full search from %.1f s", startSeconds));
            log.info("watermark-detect[{}] full search from {} s took {} ms (strongest consistency {})",
                    sessionId, String.format("%.1f", startSeconds),
                    (System.nanoTime() - windowStartNanos) / 1_000_000, response.getConsistencyScore());
            if (response.isWatermarkDetected()) {
                closest = response;
                break;
            }
            if (closest == null || response.getConsistencyScore() > closest.getConsistencyScore()) {
                closest = response;
            }
        }
        log.info("watermark-detect[{}] total request time {} ms",
                sessionId, (System.nanoTime() - requestStartNanos) / 1_000_000);
        return closest;
    }

    /**
     * Start times (seconds) of the full search's lock windows for a
     * recording of this length: 0 first, then evenly spread (see
     * MAX_LOCK_WINDOWS).
     */
    static List<Double> lockWindowStarts(double durationSeconds) {
        double span = durationSeconds - LOCK_WINDOW_MIN_REMAINING_SECONDS;
        if (span <= 0) return List.of(0.0);
        double spacing = Math.max(LOCK_WINDOW_MIN_SPACING_SECONDS, span / (MAX_LOCK_WINDOWS - 1));
        List<Double> starts = new ArrayList<>();
        for (int i = 0; i < MAX_LOCK_WINDOWS && i * spacing <= span + 1e-9; i++) {
            starts.add(i * spacing);
        }
        return starts;
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

    /**
     * One search pass per rate group, locking from {@code startSeconds} into
     * the recording, merged back into registration order. Scores are
     * comparable across groups.
     */
    private Map<String, WatermarkSearchEngine.UserScore> searchAllRateGroups(
            List<RateGroup> groups, List<DetectionConfigView> sessionConfigs,
            int hop, int analysisSize, int numBands, float[] window, Long cyclePosLimit, double startSeconds) {

        Map<String, WatermarkSearchEngine.UserScore> scoresByUser = new HashMap<>();
        for (RateGroup g : groups) {
            int start = Math.min(g.samples().length, (int) Math.round(startSeconds * g.sampleRate()));
            float[] samples = start == 0 ? g.samples() : Arrays.copyOfRange(g.samples(), start, g.samples().length);
            scoresByUser.putAll(searchEngine.findBestScoresAcrossUsers(
                    samples, g.configs(), hop, analysisSize, numBands, g.sampleRate(),
                    window, g.binToBand(), cyclePosLimit));
        }
        Map<String, WatermarkSearchEngine.UserScore> ordered = new LinkedHashMap<>();
        for (DetectionConfigView c : sessionConfigs) {
            ordered.put(c.getUserId(), scoresByUser.get(c.getUserId()));
        }
        return ordered;
    }

    /** Users ranked strongest first, and which of them reach MIN_CONSISTENCY. */
    record Decision(String strongestUserId, List<String> detectedUserIds) {
        boolean detected() { return !detectedUserIds.isEmpty(); }
    }

    /**
     * The detection decision, kept free of I/O so it can be tested directly:
     * users are ranked by consistency, and every user at or above
     * MIN_CONSISTENCY counts as detected, strongest first. More than one
     * can legitimately qualify — two participants' speakers can both reach
     * one recording — so the others are reported rather than hidden.
     */
    static Decision decide(Map<String, WatermarkSearchEngine.UserScore> scores) {
        List<String> ranked = scores.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue().consistency(), a.getValue().consistency()))
                .map(e -> e.getKey())
                .toList();
        List<String> detected = ranked.stream()
                .filter(id -> scores.get(id).consistency() >= MIN_CONSISTENCY)
                .toList();
        return new Decision(ranked.isEmpty() ? null : ranked.get(0), detected);
    }

    /**
     * Applies the decision (see decide) and builds the response DTO —
     * shared between the fast-path check and the full-search fallback so
     * both stages apply identical detection logic.
     */
    private WatermarkDetectionResponse buildDetectionResponse(
            Map<String, WatermarkSearchEngine.UserScore> allUserScores,
            List<DetectionConfigView> sessionConfigs,
            Map<String, String> userDisplayNames,
            String sessionId, int numFrames, String pathLabel) {

        Decision decision = decide(allUserScores);
        String bestUserId = decision.strongestUserId();
        WatermarkSearchEngine.UserScore best = allUserScores.get(bestUserId);
        String bestName = userDisplayNames.getOrDefault(bestUserId, bestUserId);

        String message = String.format("%s (%s). %s: '%s' | consistency=%.1f (threshold %.1f), score=%.4f",
                decision.detected() ? "Watermark detected" : "No watermark detected", pathLabel,
                decision.detected() ? "Detected user" : "Strongest", bestName,
                best.consistency(), MIN_CONSISTENCY, best.score());
        if (decision.detectedUserIds().size() > 1) {
            message += " | also above threshold: " + decision.detectedUserIds().stream().skip(1)
                    .map(id -> String.format("'%s' (consistency=%.1f)",
                            userDisplayNames.getOrDefault(id, id), allUserScores.get(id).consistency()))
                    .collect(Collectors.joining(", "));
        }

        Map<String, Double> roundedScores = new LinkedHashMap<>();
        Map<String, Double> roundedConsistency = new LinkedHashMap<>();
        for (Map.Entry<String, WatermarkSearchEngine.UserScore> e : allUserScores.entrySet()) {
            roundedScores.put(e.getKey(), round4(e.getValue().score()));
            roundedConsistency.put(e.getKey(), round2(e.getValue().consistency()));
        }

        return new WatermarkDetectionResponse(
                decision.detected() ? bestUserId : null,
                decision.detected() ? bestName : null,
                sessionId,
                round4(best.score()),
                decision.detected(),
                numFrames,
                sessionConfigs.size(),
                roundedScores,
                userDisplayNames,
                message,
                round2(best.consistency()),
                roundedConsistency);
    }

    private double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}