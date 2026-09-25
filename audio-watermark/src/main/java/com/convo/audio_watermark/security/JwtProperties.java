package com.convo.audio_watermark.security;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

// Same app.jwt.secret / JWT_SECRET as convo-backend's JwtService, which
// issues the tokens; this service only verifies them. Deliberately no
// fallback default (same in all three services): a default secret is
// published in the repo, so running with it would let anyone mint a token
// for any user. Same fail-loudly-at-startup reasoning as
// InternalServiceProperties.
@Component
@ConfigurationProperties(prefix = "app.jwt")
public class JwtProperties {

    private String secret;

    @PostConstruct
    void validate() {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "app.jwt.secret (JWT_SECRET) must be set — it verifies the login tokens "
                            + "convo-backend issues, and must match convo-backend's own JWT_SECRET.");
        }
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }
}
