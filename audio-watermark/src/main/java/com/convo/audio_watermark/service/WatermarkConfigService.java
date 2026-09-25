package com.convo.audio_watermark.service;

import com.convo.audio_watermark.entity.WatermarkConfig;
import com.convo.audio_watermark.repository.WatermarkConfigRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
public class WatermarkConfigService {

    private final WatermarkConfigRepository repository;
    private final MeetingParticipantClient participantClient;

    public WatermarkConfigService(WatermarkConfigRepository repository, MeetingParticipantClient participantClient) {
        this.repository = repository;
        this.participantClient = participantClient;
    }

    // Loose bounds on a real AudioContext's rate; only meant to keep garbage
    // out of the resampling step at detection time.
    private static final int MIN_SAMPLE_RATE = 8000;
    private static final int MAX_SAMPLE_RATE = 384000;

    @Transactional
    public WatermarkConfig getOrCreateConfig(String roomId, String userId, Integer sampleRate) {

        if (sampleRate != null && (sampleRate < MIN_SAMPLE_RATE || sampleRate > MAX_SAMPLE_RATE)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "sampleRate must be between " + MIN_SAMPLE_RATE + " and " + MAX_SAMPLE_RATE + " Hz");
        }

        UUID userUuid = UUID.fromString(userId);

        // Confirms this user is actually a participant of this meeting —
        // convo-backend is the source of truth for that, not a local join,
        // now that this service holds no foreign key into its tables.
        boolean isParticipant = participantClient.listParticipants(roomId).stream()
                .anyMatch(p -> p.userId().equals(userUuid));
        if (!isParticipant) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "No meeting participant found for roomId=" + roomId + " and userId=" + userId);
        }

        // Check if seed already exists for this meeting participant
        Optional<WatermarkConfig> existing = repository.findByMeetingCodeAndUserId(roomId, userUuid);

        if (existing.isPresent()) {
            WatermarkConfig config = existing.get();
            // Latest device wins: a participant who rejoins on hardware with
            // a different default rate embeds at the new rate from then on.
            // A recording made on the earlier device would then be resampled
            // to the wrong rate at detection; one rate per participant per
            // meeting is accepted as good enough.
            if (sampleRate != null && !sampleRate.equals(config.getSampleRate())) {
                config.setSampleRate(sampleRate);
                return repository.save(config);
            }
            return config;
        }

        // Generate new unique seed
        WatermarkConfig config = new WatermarkConfig();
        config.setMeetingCode(roomId);
        config.setUserId(userUuid);
        config.setSeed(generateUniqueSeed());
        config.setAlpha(4.0);
        config.setFrameSize(256);
        config.setCreatedAt(LocalDateTime.now());
        config.setAnalysisWindowSize(512); 
        config.setNumBands(24);
        // Repeating-tag period — MUST match what audio-processor.worklet.js
        // uses (config.cycleSeconds; the worklet silently falls back to 8
        // if this value is ever missing, which would break detection, so it
        // must always be sent). This bounds the BLIND exhaustive search (see
        // WatermarkSearchEngine.findBestScoresAcrossUsers) to a fixed
        // window for EXTERNAL/uncooperative recordings — that search is what
        // pushed this value down as low as 1.0 during latency tuning (see
        // git history), trading away robustness (shorter repeat cycle) for
        // speed. Now that in-app recordings reset the embedder's cycle
        // position to 0 at capture start (see the 'reset-prng' worklet
        // message) and detect() tries a cheap near-cyclePos=0 fast path
        // first, the exhaustive blind search is only the FALLBACK — used
        // for external recordings or if the fast path misses — so its speed
        // matters far less than it used to. Set back up to 4.0 for better
        // robustness; still short of the old 8.0 default per product
        // preference.
        config.setCycleSeconds(4.0);
        config.setSampleRate(sampleRate);

        return repository.save(config);
    }

    // Generate a unique 6-character alphanumeric seed
    private String generateUniqueSeed() {
        String seed;
        do {
            seed = UUID.randomUUID().toString()
                    .replace("-", "")
                    .substring(0, 6)
                    .toUpperCase();
        } while (repository.existsBySeed(seed));
        return seed;
    }
}