package com.convo.audio_watermark.controller;

import com.convo.audio_watermark.dto.WatermarkDetectionResponse;
import com.convo.audio_watermark.service.WatermarkDetectionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.IOException;
import java.util.Map;

/**
 * REST controller for the watermark detection endpoint.
 *
 * <pre>
 * POST /api/watermark/detect
 *   Content-Type: multipart/form-data
 *   Parts:
 *     audio     – the audio file to analyse (WAV recommended)
 *     sessionId – the session / meeting ID to look up registered users
 *
 * Response 200:
 * {
 *   "detectedUser":       "u001",          // null if no watermark found
 *   "sessionId":          "abc123",
 *   "correlationScore":   0.9500,          // best candidate score, normalised [−1, +1]
 *   "watermarkDetected":  true,
 *   "totalFramesAnalyzed": 412,
 *   "totalUsersChecked":   3,
 *   "allUserScores": { "u001": 0.95, "u002": 0.02, "u003": -0.01 },
 *   "message":            "Watermark detected. Best match: user 'u001' ..."
 * }
 * </pre>
 */
@RestController
@RequestMapping("/api/watermark")
public class WatermarkDetectionController {

    @Autowired
    private WatermarkDetectionService detectionService;

    /**
     * Detect whose watermark is embedded in the uploaded audio file.
     *
     * @param audioFile the audio file (multipart part named "audio")
     * @param sessionId the session / meeting ID (multipart part named "sessionId")
     * @return {@link WatermarkDetectionResponse} with detected user and evaluation metrics
     */
    @PostMapping(value = "/detect", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> detectWatermark(
            @RequestPart("audio") MultipartFile audioFile,
            @RequestParam("sessionId") String sessionId) {

        // Basic input validation
        if (audioFile == null || audioFile.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Audio file must not be empty."));
        }
        if (sessionId == null || sessionId.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "sessionId must not be blank."));
        }

        try {
            WatermarkDetectionResponse response = detectionService.detect(audioFile, sessionId);
            return ResponseEntity.ok(response);

        } catch (UnsupportedAudioFileException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of(
                            "error", "Unsupported audio format. Please upload a WAV file.",
                            "detail", e.getMessage()));

        } catch (IOException e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of(
                            "error", "Failed to read audio file.",
                            "detail", e.getMessage()));

        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of(
                            "error", "An unexpected error occurred during detection.",
                            "detail", e.getMessage()));
        }
    }
}