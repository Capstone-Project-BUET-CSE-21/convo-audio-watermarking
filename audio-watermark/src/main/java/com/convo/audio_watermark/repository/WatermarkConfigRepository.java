package com.convo.audio_watermark.repository;

import com.convo.audio_watermark.entity.WatermarkConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.Optional;

public interface WatermarkConfigRepository extends JpaRepository<WatermarkConfig, Long> {
    boolean existsBySeed(String seed);

    Optional<WatermarkConfig> findByMeetingUserId(Long meetingUserId);

    @Query(value = """
            select mu.id
            from meeting_user mu
            join meetings m on mu.meeting_id = m.id
            where m.meeting_code = :meetingCode
              and mu.user_id = cast(:userId as uuid)
            """, nativeQuery = true)
    Optional<Long> findMeetingUserIdByMeetingCodeAndUserId(String meetingCode, String userId);

    interface DetectionConfigProjection {
        String getUserId();

        String getDisplayName();

        String getSeed();

        Double getAlpha();

        Integer getFrameSize();

        Integer getAnalysisWindowSize();
 
        Integer getNumBands();

        Double getCycleSeconds();
    }

    @Query(value = """
            select
                cast(mu.user_id as text) as userId,
                coalesce(nullif(u.display_name, ''), cast(mu.user_id as text)) as displayName,
                wc.seed as seed,
                wc.alpha as alpha,
                wc.frame_size as frameSize,
                wc.analysis_window_size as analysisWindowSize,
                wc.num_bands as numBands,
                wc.cycle_seconds as cycleSeconds
            from watermark_config wc
            join meeting_user mu on wc.meeting_user_id = mu.id
            join users u on mu.user_id = u.id
            join meetings m on mu.meeting_id = m.id
            where m.meeting_code = :meetingCode
            """, nativeQuery = true)
    List<DetectionConfigProjection> findDetectionConfigsByMeetingCode(String meetingCode);
}