package com.convo.audio_watermark.repository;

import com.convo.audio_watermark.entity.WatermarkConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WatermarkConfigRepository extends JpaRepository<WatermarkConfig, Long> {
    boolean existsBySeed(String seed);

    Optional<WatermarkConfig> findByMeetingCodeAndUserId(String meetingCode, UUID userId);

    // All configs already issued for a meeting — joined in Java against
    // convo-backend's participant list (MeetingParticipantClient) rather
    // than a native SQL join, since this service no longer has a foreign
    // key into convo-backend's tables to join against. See
    // WatermarkDetectionService.
    List<WatermarkConfig> findByMeetingCode(String meetingCode);
}
