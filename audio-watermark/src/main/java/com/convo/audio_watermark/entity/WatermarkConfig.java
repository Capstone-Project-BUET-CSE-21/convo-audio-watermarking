package com.convo.audio_watermark.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

// Keyed by (meeting_code, user_id) directly rather than a foreign
// meeting_user.id — this service no longer reaches into convo-backend's
// tables (not even by shared surrogate key), so it needs an identity for
// "which participant, in which meeting" that it can resolve entirely on
// its own. meeting_code + user_id are exactly what convo-backend's
// internal API (see MeetingParticipantClient) already hands back, so no
// translation step is needed either.
@Entity
@Table(name = "watermark_config", uniqueConstraints = @UniqueConstraint(
        name = "uq_watermark_config_meeting_user", columnNames = {"meeting_code", "user_id"}))
public class WatermarkConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "meeting_code", nullable = false, length = 64)
    private String meetingCode;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "seed", nullable = false, unique = true)
    private String seed;

    @Column(name = "alpha", nullable = false)
    private Double alpha;

    @Column(name = "frame_size", nullable = false)
    private Integer frameSize;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    // Width of the analysis window used for masking computation (hop * 2 for 50% overlap-add).
    @Column(name = "analysis_window_size", nullable = false)
    private Integer analysisWindowSize;
 
    // Number of Bark critical bands used to compute the masking threshold.
    @Column(name = "num_bands", nullable = false)
    private Integer numBands;

    // Repeating-tag period, in seconds. Must match what the embedder
    // (audio-processor.worklet.js, config.cycleSeconds) actually used —
    // this is what bounds the detector's synchronization search to a
    // fixed window instead of one that grows with call length.
    @Column(name = "cycle_seconds", nullable = false)
    private Double cycleSeconds;

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getMeetingCode() {
        return meetingCode;
    }

    public void setMeetingCode(String meetingCode) {
        this.meetingCode = meetingCode;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
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

    public Integer getAnalysisWindowSize() {
        return analysisWindowSize;
    }
 
    public void setAnalysisWindowSize(Integer analysisWindowSize) {
        this.analysisWindowSize = analysisWindowSize;
    }
 
    public Integer getNumBands() {
        return numBands;
    }
 
    public void setNumBands(Integer numBands) {
        this.numBands = numBands;
    }

    public Double getCycleSeconds() {
        return cycleSeconds;
    }

    public void setCycleSeconds(Double cycleSeconds) {
        this.cycleSeconds = cycleSeconds;
    }
}