package com.convo.audio_watermark.controller;

import com.convo.audio_watermark.entity.WatermarkConfig;
import com.convo.audio_watermark.service.WatermarkConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/watermark")
public class WatermarkConfigController {

    @Autowired
    private WatermarkConfigService service;

    @GetMapping("/config")
    public Map<String, Object> getConfig(
            @RequestParam String roomId,
            @RequestParam String userId) {
        WatermarkConfig config = service.getOrCreateConfig(roomId, userId);
        System.out.println("Returning watermark config for roomId=" + roomId + " userId=" + userId);

        return Map.of(
                "seed", config.getSeed(),
                "alpha", config.getAlpha(),
                "frameSize", config.getFrameSize());
    }
}