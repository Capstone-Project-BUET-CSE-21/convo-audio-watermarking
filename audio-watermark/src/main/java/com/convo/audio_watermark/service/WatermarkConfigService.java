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
        // is ever missing). This is what bounds the detector's search to a
        // fixed window instead of one that grows with call length — see
        // WatermarkDetectionService. The detector's cyclePos search is
        // EXHAUSTIVE (hopsPerCycle = cycleSeconds * sampleRate / frameSize
        // candidates, scanned one-by-one — see findBestScoresAcrossUsers'
        // doc comment on why it can't be subsampled), so this value directly
        // and linearly controls detection latency: halving it roughly halves
        // search time. Keep it as short as your expected clip length allows,
        // not longer than needed.
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