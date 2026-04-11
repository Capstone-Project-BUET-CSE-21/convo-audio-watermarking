package com.convo.audio_watermark;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import io.github.cdimascio.dotenv.Dotenv;

@SpringBootApplication
public class AudioWatermarkApplication {

    private static String resolveValue(String key, Dotenv local) {
        return local.get(key);
    }

    private static void setPropertyIfPresent(String key, String value) {
        if (value != null && !value.isBlank()) {
            System.setProperty(key, value);
        }
    }

    public static void main(String[] args) {
        Dotenv localDotenv = Dotenv.configure()
                .ignoreIfMalformed()
                .ignoreIfMissing()
                .load();

        setPropertyIfPresent("spring-datasource-url", resolveValue("DB_URL", localDotenv));
        setPropertyIfPresent("spring-datasource-username", resolveValue("DB_USER", localDotenv));
        setPropertyIfPresent("spring-datasource-password", resolveValue("DB_PASS", localDotenv));

        SpringApplication.run(AudioWatermarkApplication.class, args);
    }
}