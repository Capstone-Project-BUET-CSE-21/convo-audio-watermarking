package com.convo.audio_watermark.controller;

import com.convo.audio_watermark.entity.WatermarkConfig;
import com.convo.audio_watermark.service.WatermarkConfigService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/watermark")
public class WatermarkConfigController {

    private final WatermarkConfigService service;

    public WatermarkConfigController(WatermarkConfigService service) {
        this.service = service;
    }

    @GetMapping("/config")
    public Map<String, Object> getConfig(
            @RequestParam String roomId,
            @RequestParam String userId) {
        WatermarkConfig config = service.getOrCreateConfig(roomId, userId);

        return Map.of(
                "seed", config.getSeed(),
                "alpha", config.getAlpha(),
                "frameSize", config.getFrameSize(),
                "analysisWindowSize", config.getAnalysisWindowSize(),
                "numBands", config.getNumBands(),
                "cycleSeconds", config.getCycleSeconds());
    }
}