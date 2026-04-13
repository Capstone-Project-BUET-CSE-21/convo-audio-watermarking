package com.convo.audio_watermark.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "watermark_config")
public class WatermarkConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "meeting_user_id", nullable = false)
    private Long meetingUserId;

    @Column(name = "seed", nullable = false, unique = true)
    private String seed;

    @Column(name = "alpha", nullable = false)
    private Double alpha;

    @Column(name = "frame_size", nullable = false)
    private Integer frameSize;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getMeetingUserId() {
        return meetingUserId;
    }

    public void setMeetingUserId(Long meetingUserId) {
        this.meetingUserId = meetingUserId;
    }

    public String getSeed() {
        return seed;
    }

    public void setSeed(String seed) {
        this.seed = seed;
    }

    public Double getAlpha() {
        return alpha;
    }

    public void setAlpha(Double alpha) {
        this.alpha = alpha;
    }

    public Integer getFrameSize() {
        return frameSize;
    }

    public void setFrameSize(Integer frameSize) {
        this.frameSize = frameSize;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}