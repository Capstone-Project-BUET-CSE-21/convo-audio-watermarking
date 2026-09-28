package com.convo.audio_watermark.service;

import com.convo.audio_watermark.config.InternalServiceProperties;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

// Calls convo-backend's internal, server-to-server API for meeting
// membership instead of this service holding its own copy of it or
// joining across schemas directly against convo-backend's tables (the
// meeting_user_id foreign key this replaced). Same pattern, same shared
// credential, as convo-file-sharing's SessionParticipantService.
@Service
public class MeetingParticipantClient {

    public record Participant(UUID userId, String displayName, Instant joinedAt) {}

    private final RestClient restClient;
    private final InternalServiceProperties properties;

    // Without an explicit timeout, a hung/unreachable convo-backend hangs
    // this call (and therefore /config and /detect) indefinitely instead of
    // failing fast — /detect in particular forks heavy parallel search work
    // right after this returns, so a stuck internal call here is worse than
    // it looks.
    private static final int CONNECT_TIMEOUT_MS = 3_000;
    private static final int READ_TIMEOUT_MS = 5_000;

    public MeetingParticipantClient(InternalServiceProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        requestFactory.setReadTimeout(READ_TIMEOUT_MS);
        this.restClient = RestClient.builder()
                .baseUrl(properties.getBackendBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    /**
     * @return the meeting's participants, or an empty list if convo-backend
     *     has never heard of this meeting code.
     */
    public List<Participant> listParticipants(String meetingCode) {
        try {
            Participant[] participants = restClient.get()
                    .uri("/api/backend/internal/meetings/{meetingCode}/participants", meetingCode)
                    .header("X-Internal-Service-Key", properties.getServiceKey())
                    .retrieve()
                    .body(Participant[].class);
            return participants == null ? List.of() : List.of(participants);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return List.of();
            }
            throw e;
        }
    }
}
