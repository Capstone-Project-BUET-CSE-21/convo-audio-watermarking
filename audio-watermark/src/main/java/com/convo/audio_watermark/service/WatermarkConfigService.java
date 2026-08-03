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

    public WatermarkConfigService(WatermarkConfigRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public WatermarkConfig getOrCreateConfig(String roomId, String userId) {

        Long meetingUserId = repository.findMeetingUserIdByMeetingCodeAndUserId(roomId, userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "No meeting_user mapping found for roomId=" + roomId + " and userId=" + userId));

        // Check if seed already exists for this meeting participant
        Optional<WatermarkConfig> existing = repository.findByMeetingUserId(meetingUserId);

        if (existing.isPresent()) {
            return existing.get();
        }

        // Generate new unique seed
        WatermarkConfig config = new WatermarkConfig();
        config.setMeetingUserId(meetingUserId);
        config.setSeed(generateUniqueSeed());
        config.setAlpha(4.0);
        config.setFrameSize(256);
        config.setCreatedAt(LocalDateTime.now());
        config.setAnalysisWindowSize(512); 
        config.setNumBands(24);
        // Repeating-tag period — MUST match what audio-processor.worklet.js
        // uses (config.cycleSeconds, defaults to 8 there too if this value
        // is ever missing). This bounds the BLIND exhaustive search (see
        // WatermarkDetectionService.findBestScoresAcrossUsers) to a fixed
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