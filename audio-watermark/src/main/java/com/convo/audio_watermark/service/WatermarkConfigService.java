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
        // uses (config.cycleSeconds, defaults to 20 there too). This is what
        // bounds the detector's search to a fixed window instead of one that
        // grows with call length — see WatermarkDetectionService. Set with
        // some headroom above your actual expected clip length, not longer
        // than needed — see conversation notes on why shortening this
        // doesn't meaningfully speed up detection (COARSE_CYCLE_CANDIDATES
        // already auto-scales the search stride to the cycle length).
        config.setCycleSeconds(8.0);

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