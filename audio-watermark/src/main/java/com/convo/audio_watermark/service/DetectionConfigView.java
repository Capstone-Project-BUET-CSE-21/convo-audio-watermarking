package com.convo.audio_watermark.service;

// Drop-in replacement for the old WatermarkConfigRepository.DetectionConfigProjection
// (a native-SQL-backed JPA projection) — same accessor contract, but built
// by joining a local WatermarkConfig row against a convo-backend
// participant (MeetingParticipantClient.Participant) in Java, in
// WatermarkDetectionService, instead of a native cross-service SQL join.
// Every downstream consumer of these values (the DSP search/scoring code)
// is unchanged.
public class DetectionConfigView {

    private final String userId;
    private final String displayName;
    private final String seed;
    private final Double alpha;
    private final Integer frameSize;
    private final Integer analysisWindowSize;
    private final Integer numBands;
    private final Double cycleSeconds;
    private final Integer sampleRate; // null for configs issued before clients reported it

    public DetectionConfigView(
            String userId,
            String displayName,
            String seed,
            Double alpha,
            Integer frameSize,
            Integer analysisWindowSize,
            Integer numBands,
            Double cycleSeconds,
            Integer sampleRate) {
        this.userId = userId;
        this.displayName = displayName;
        this.seed = seed;
        this.alpha = alpha;
        this.frameSize = frameSize;
        this.analysisWindowSize = analysisWindowSize;
        this.numBands = numBands;
        this.cycleSeconds = cycleSeconds;
        this.sampleRate = sampleRate;
    }

    public String getUserId() { return userId; }
    public String getDisplayName() { return displayName; }
    public String getSeed() { return seed; }
    public Double getAlpha() { return alpha; }
    public Integer getFrameSize() { return frameSize; }
    public Integer getAnalysisWindowSize() { return analysisWindowSize; }
    public Integer getNumBands() { return numBands; }
    public Double getCycleSeconds() { return cycleSeconds; }
    public Integer getSampleRate() { return sampleRate; }
}
