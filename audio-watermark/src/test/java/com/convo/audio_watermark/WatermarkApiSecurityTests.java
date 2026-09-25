package com.convo.audio_watermark;

import com.convo.audio_watermark.entity.WatermarkConfig;
import com.convo.audio_watermark.repository.WatermarkConfigRepository;
import com.convo.audio_watermark.service.MeetingParticipantClient;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who may call what: /config needs a convo-backend JWT and belongs to the
 * token's user (never a query param); /detect is deliberately open.
 */
@SpringBootTest
@AutoConfigureMockMvc
class WatermarkApiSecurityTests {

    // Must match app.jwt.secret in src/test/resources/application.properties.
    private static final String SECRET = "test-only-secret-key-at-least-32-bytes-long";
    private static final String ROOM = "security-test-room";
    private static final UUID MEMBER = UUID.randomUUID();
    private static final UUID OUTSIDER = UUID.randomUUID();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private WatermarkConfigRepository repository;

    @MockitoBean
    private MeetingParticipantClient participantClient;

    @BeforeEach
    void stubParticipants() {
        when(participantClient.listParticipants(ROOM)).thenReturn(
                List.of(new MeetingParticipantClient.Participant(MEMBER, "Member", Instant.now())));
    }

    @Test
    void configWithoutTokenIs401() throws Exception {
        mvc.perform(get("/api/audio-watermark/config").param("roomId", ROOM))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void configWithTokenSignedByAnotherSecretIs401() throws Exception {
        mvc.perform(get("/api/audio-watermark/config").param("roomId", ROOM)
                        .header("Authorization", bearer(MEMBER, "some-other-secret-that-is-also-32-bytes-plus")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void configIsIssuedToTheTokensUserAndStoresSampleRate() throws Exception {
        mvc.perform(get("/api/audio-watermark/config").param("roomId", ROOM).param("sampleRate", "44100")
                        .header("Authorization", bearer(MEMBER, SECRET)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seed").isNotEmpty());

        WatermarkConfig stored = repository.findByMeetingCodeAndUserId(ROOM, MEMBER).orElseThrow();
        assertThat(stored.getSampleRate()).isEqualTo(44100);
    }

    @Test
    void configForSomeoneElsesUserIdIs403() throws Exception {
        mvc.perform(get("/api/audio-watermark/config").param("roomId", ROOM).param("userId", OUTSIDER.toString())
                        .header("Authorization", bearer(MEMBER, SECRET)))
                .andExpect(status().isForbidden());
    }

    @Test
    void configForNonParticipantIs404() throws Exception {
        mvc.perform(get("/api/audio-watermark/config").param("roomId", ROOM)
                        .header("Authorization", bearer(OUTSIDER, SECRET)))
                .andExpect(status().isNotFound());
    }

    @Test
    void configWithOutOfRangeSampleRateIs400() throws Exception {
        mvc.perform(get("/api/audio-watermark/config").param("roomId", ROOM).param("sampleRate", "12")
                        .header("Authorization", bearer(MEMBER, SECRET)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void detectRunsWithoutToken() throws Exception {
        // Detection needs at least one issued config to search against.
        mvc.perform(get("/api/audio-watermark/config").param("roomId", ROOM)
                        .header("Authorization", bearer(MEMBER, SECRET)))
                .andExpect(status().isOk());

        mvc.perform(multipart("/api/audio-watermark/detect").file(silentWav()).param("sessionId", ROOM))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.watermarkDetected").value(false))
                .andExpect(jsonPath("$.totalUsersChecked").value(1));
    }

    private static String bearer(UUID userId, String secret) {
        Instant now = Instant.now();
        String jwt = Jwts.builder()
                .subject("someone@example.com")
                .claim("uid", userId.toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(300)))
                .signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)))
                .compact();
        return "Bearer " + jwt;
    }

    /** 50ms of silence at 48kHz — long enough to decode, short enough to search in well under a second. */
    private static MockMultipartFile silentWav() throws Exception {
        AudioFormat format = new AudioFormat(48000f, 16, 1, true, false);
        byte[] pcm = new byte[2400 * 2];
        ByteArrayOutputStream wav = new ByteArrayOutputStream();
        AudioSystem.write(new AudioInputStream(new ByteArrayInputStream(pcm), format, 2400),
                AudioFileFormat.Type.WAVE, wav);
        return new MockMultipartFile("audio", "silence.wav", "audio/wav", wav.toByteArray());
    }
}
