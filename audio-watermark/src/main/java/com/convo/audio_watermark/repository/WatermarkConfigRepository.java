package com.convo.audio_watermark.repository;

import com.convo.audio_watermark.entity.WatermarkConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
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

    @Query(value = """
            select wc.*
            from watermark_config wc
            join meeting_user mu on wc.meeting_user_id = mu.id
            join meetings m on mu.meeting_id = m.id
            where m.meeting_code = :meetingCode
            """, nativeQuery = true)
    List<WatermarkConfig> findByMeetingCode(String meetingCode);

    interface DetectionConfigProjection {
        String getUserId();

        String getSeed();

        Double getAlpha();

        Integer getFrameSize();
    }

    @Query(value = """
            select
                cast(mu.user_id as text) as userId,
                wc.seed as seed,
                wc.alpha as alpha,
                wc.frame_size as frameSize
            from watermark_config wc
            join meeting_user mu on wc.meeting_user_id = mu.id
            join meetings m on mu.meeting_id = m.id
            where m.meeting_code = :meetingCode
            """, nativeQuery = true)
    List<DetectionConfigProjection> findDetectionConfigsByMeetingCode(String meetingCode);
}