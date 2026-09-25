package com.convo.audio_watermark.controller;

import com.convo.audio_watermark.entity.WatermarkConfig;
import com.convo.audio_watermark.security.CurrentUser;
import com.convo.audio_watermark.service.WatermarkConfigService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/audio-watermark")
public class WatermarkConfigController {

    private final WatermarkConfigService service;

    public WatermarkConfigController(WatermarkConfigService service) {
        this.service = service;
    }

    // The participant is whoever the JWT says (CurrentUser), never a query
    // parameter: the response is that participant's secret seed. userId is
    // still accepted, and must match, only so clients that send it keep
    // working. sampleRate is the embedder AudioContext's rate, optional only
    // so older clients keep working; see WatermarkConfig.sampleRate.
    @GetMapping("/config")
    public Map<String, Object> getConfig(
            @RequestParam String roomId,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) Integer sampleRate) {
        UUID callerId = CurrentUser.id();
        if (userId != null && !userId.equalsIgnoreCase(callerId.toString())) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "userId does not match the authenticated user");
        }
        WatermarkConfig config = service.getOrCreateConfig(roomId, callerId.toString(), sampleRate);

        return Map.of(
                "seed", config.getSeed(),
                "alpha", config.getAlpha(),
                "frameSize", config.getFrameSize(),
                "analysisWindowSize", config.getAnalysisWindowSize(),
                "numBands", config.getNumBands(),
                "cycleSeconds", config.getCycleSeconds());
    }
}