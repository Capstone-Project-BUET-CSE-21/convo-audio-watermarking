package com.convo.audio_watermark.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig {

    @Value("${MONA_FRONTEND_URL:}")
    private String monaFrontendUrl;

    @Value("${FARU_FRONTEND_URL:}")
    private String faruFrontendUrl;

    @Value("${DEBO_FRONTEND_URL:}")
    private String deboFrontendUrl;

    @Value("${TABA_FRONTEND_URL:}")
    private String tabaFrontendUrl;

    @Value("${ANIS_FRONTEND_URL:}")
    private String anisFrontendUrl;

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/**")
                        .allowedOrigins(buildAllowedOrigins())
                        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                        .allowedHeaders("*");
            }
        };
    }

    private String[] buildAllowedOrigins() {
        List<String> origins = new ArrayList<>();
        addIfPresent(origins, monaFrontendUrl);
        addIfPresent(origins, faruFrontendUrl);
        addIfPresent(origins, deboFrontendUrl);
        addIfPresent(origins, tabaFrontendUrl);
        addIfPresent(origins, anisFrontendUrl);
        origins.add("http://localhost:5173");
        return origins.toArray(new String[0]);
    }

    private void addIfPresent(List<String> origins, String value) {
        if (value != null && !value.isBlank()) {
            origins.add(value);
        }
    }
}