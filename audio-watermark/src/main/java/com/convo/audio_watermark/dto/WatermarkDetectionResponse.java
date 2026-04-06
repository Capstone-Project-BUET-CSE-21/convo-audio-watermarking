package com.convo.audio_watermark.dto;

import java.util.Map;

public class WatermarkDetectionResponse {

    private String detectedUser;
    private String sessionId;
    private double correlationScore;
    private boolean watermarkDetected;
    private int totalFramesAnalyzed;
    private int totalUsersChecked;
    private Map<String, Double> allUserScores; // userId -> score, for full transparency
    private String message;

    public WatermarkDetectionResponse() {}

    public WatermarkDetectionResponse(
            String detectedUser,
            String sessionId,
            double correlationScore,
            boolean watermarkDetected,
            int totalFramesAnalyzed,
            int totalUsersChecked,
            Map<String, Double> allUserScores,
            String message) {
        this.detectedUser = detectedUser;
        this.sessionId = sessionId;
        this.correlationScore = correlationScore;
        this.watermarkDetected = watermarkDetected;
        this.totalFramesAnalyzed = totalFramesAnalyzed;
        this.totalUsersChecked = totalUsersChecked;
        this.allUserScores = allUserScores;
        this.message = message;
    }

    public String getDetectedUser() { return detectedUser; }
    public void setDetectedUser(String detectedUser) { this.detectedUser = detectedUser; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public double getCorrelationScore() { return correlationScore; }
    public void setCorrelationScore(double correlationScore) { this.correlationScore = correlationScore; }

    public boolean isWatermarkDetected() { return watermarkDetected; }
    public void setWatermarkDetected(boolean watermarkDetected) { this.watermarkDetected = watermarkDetected; }

    public int getTotalFramesAnalyzed() { return totalFramesAnalyzed; }
    public void setTotalFramesAnalyzed(int totalFramesAnalyzed) { this.totalFramesAnalyzed = totalFramesAnalyzed; }

    public int getTotalUsersChecked() { return totalUsersChecked; }
    public void setTotalUsersChecked(int totalUsersChecked) { this.totalUsersChecked = totalUsersChecked; }

    public Map<String, Double> getAllUserScores() { return allUserScores; }
    public void setAllUserScores(Map<String, Double> allUserScores) { this.allUserScores = allUserScores; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}