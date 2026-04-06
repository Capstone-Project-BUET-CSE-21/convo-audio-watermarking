package com.convo.audio_watermark;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import io.github.cdimascio.dotenv.Dotenv;

@SpringBootApplication
public class AudioWatermarkApplication {

    public static void main(String[] args) {
        Dotenv localDotenv = Dotenv.configure()
                .ignoreIfMalformed()
                .ignoreIfMissing()
                .load();
        Dotenv sharedDotenv = Dotenv.configure()
                .directory("..")
                .ignoreIfMalformed()
                .ignoreIfMissing()
                .load();

        setPropertyIfPresent("spring.datasource.url", resolveValue("DB_URL", localDotenv, sharedDotenv));
        setPropertyIfPresent("spring.datasource.username", resolveValue("DB_USER", localDotenv, sharedDotenv));
        setPropertyIfPresent("spring.datasource.password", resolveValue("DB_PASS", localDotenv, sharedDotenv));

        setPropertyIfPresent("MONA_FRONTEND_URL", resolveValue("MONA_FRONTEND_URL", sharedDotenv, localDotenv));
        setPropertyIfPresent("FARU_FRONTEND_URL", resolveValue("FARU_FRONTEND_URL", sharedDotenv, localDotenv));
        setPropertyIfPresent("DEBO_FRONTEND_URL", resolveValue("DEBO_FRONTEND_URL", sharedDotenv, localDotenv));
        setPropertyIfPresent("TABA_FRONTEND_URL", resolveValue("TABA_FRONTEND_URL", sharedDotenv, localDotenv));
        setPropertyIfPresent("ANIS_FRONTEND_URL", resolveValue("ANIS_FRONTEND_URL", sharedDotenv, localDotenv));

        SpringApplication.run(AudioWatermarkApplication.class, args);
    }

    private static String resolveValue(String key, Dotenv primary, Dotenv fallback) {
        String value = primary.get(key);
        if (value == null || value.isBlank()) {
            value = fallback.get(key);
        }
        return value;
    }

    private static void setPropertyIfPresent(String key, String value) {
        if (value != null && !value.isBlank()) {
            System.setProperty(key, value);
        }
    }

}