package com.convo.audio_watermark.service;

import com.convo.audio_watermark.entity.WatermarkConfig;
import com.convo.audio_watermark.repository.WatermarkConfigRepository;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
public class WatermarkConfigService {

    @Autowired
    private WatermarkConfigRepository repository;

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
        config.setAlpha(0.002);
        config.setFrameSize(256);
        config.setCreatedAt(LocalDateTime.now());

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