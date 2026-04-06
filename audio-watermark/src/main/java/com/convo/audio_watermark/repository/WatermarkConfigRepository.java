package com.convo.audio_watermark.repository;

import com.convo.audio_watermark.entity.WatermarkConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;
import java.util.List;

@Repository
public interface WatermarkConfigRepository extends JpaRepository<WatermarkConfig, Long> {
    boolean existsBySeed(String seed);
    Optional<WatermarkConfig> findBySessionIdAndUserId(String sessionId, String userId);
    List<WatermarkConfig> findBySessionId(String sessionId);
}