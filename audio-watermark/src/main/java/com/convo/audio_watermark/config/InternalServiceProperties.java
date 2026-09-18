package com.convo.audio_watermark.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

// Config for calling convo-backend's internal, server-to-server API
// (see MeetingParticipantClient). serviceKey must match convo-backend's
// own app.internal.service-key exactly; no fallback default, same
// reasoning as convo-backend's own InternalServiceProperties (fail loudly
// at startup rather than silently sending an empty/guessable header).
//
// @Component here (rather than @ConfigurationPropertiesScan on
// AudioWatermarkApplication, which this service doesn't currently use) is
// what registers this as a bean — component scanning already covers it.
@Component
@ConfigurationProperties(prefix = "app.internal")
public class InternalServiceProperties {

    private String serviceKey;
    private String backendBaseUrl;

    @PostConstruct
    void validate() {
        if (serviceKey == null || serviceKey.isBlank()) {
            throw new IllegalStateException(
                    "app.internal.service-key (INTERNAL_SERVICE_KEY) must be set — "
                            + "it authenticates this service's calls to convo-backend's internal API, "
                            + "and must match convo-backend's own app.internal.service-key.");
        }
        if (backendBaseUrl == null || backendBaseUrl.isBlank()) {
            throw new IllegalStateException(
                    "app.internal.backend-base-url (CONVO_BACKEND_URL) must be set — "
                            + "it's where this service sends its internal API calls to convo-backend.");
        }
    }

    public String getServiceKey() {
        return serviceKey;
    }

    public void setServiceKey(String serviceKey) {
        this.serviceKey = serviceKey;
    }

    public String getBackendBaseUrl() {
        return backendBaseUrl;
    }

    public void setBackendBaseUrl(String backendBaseUrl) {
        this.backendBaseUrl = backendBaseUrl;
    }
}
