# Convo Audio Watermark Service

Spring Boot service behind Convo's leak tracing. When a recording of a meeting
leaks, it identifies **which participant's client the recording came from**.

Related repositories:

* Frontend (hosts the embedder): <https://github.com/Capstone-Project-BUET-CSE-21/convo-frontend>
* Main backend (auth, signalling, meeting membership): <https://github.com/Capstone-Project-BUET-CSE-21/convo-backend>

---

## How it works

The watermark is embedded in the browser, not here. Each participant's client
mixes their own mic and every remote peer into one stream, then runs it
through an AudioWorklet (`convo-frontend/frontend/public/audio-watermark/audio-processor.worklet.js`)
before it reaches their speakers. The worklet adds noise that is:

* **keyed per participant per meeting**: a seed issued by this service drives a mulberry32 PRNG;
* **shaped to be inaudible**: per-frame spectral magnitude is set from a simple Bark-band masking model of the audio itself, so only the per-bin *phase* carries the key;
* **repeating every `cycleSeconds`** (4 s): any recording longer than one cycle contains a full repetition, so detection only searches one cycle's worth of positions no matter how long the call ran.

Peers receive each other's raw audio. Only what comes out of *your* speakers,
and therefore what your in-app recorder or a phone held up to your laptop
captures, carries *your* watermark.

This service does two things:

1. **Issues configs.** It gives each (meeting, participant) pair a unique seed plus the DSP parameters, after confirming membership with convo-backend.
2. **Detects.** Given a recording and a meeting code, it rebuilds each registered participant's expected watermark from the recording itself and correlates.
   * **Alignment search.** An exhaustive search over the cycle position, plus a coarse-to-fine search over the sample offset.
   * **Fast path.** In-app recordings reset the embedder's cycle to 0 when capture starts, so the search around position 0 is tried first.
   * **Final score.** Each user's final score is computed on held-out audio with clock-drift tracking.

| Class | Role |
|---|---|
| `WatermarkConfigService` | Issues and stores per-participant configs (`watermark_config` table) |
| `WatermarkDetectionService` | Orchestrates detection: participants → decode → fast path → exhaustive fallback → threshold/margin decision |
| `WatermarkSearchEngine` | The synchronization search and drift-tracked final scoring |
| `WatermarkDsp` | FFT, masking model, PRNG, correlation. An exact Java port of the JS worklet |
| `WatermarkAudioDecoder` | WAV/AIFF/AU natively; MP3/AAC/M4A/Opus/etc. via ffmpeg |
| `MeetingParticipantClient` | Calls convo-backend's internal participants API |

The class-level Javadoc in `WatermarkSearchEngine` explains each design decision
and the bug that motivated it.

---

## API

`/config` requires `Authorization: Bearer <JWT>`, where the token is the same
one convo-backend issues at login (verified with the shared `JWT_SECRET`). A
missing, invalid or expired token gets `401`. The caller's identity comes from
the token's `uid` claim, never from a request parameter. `/detect` needs no
authentication.

### `GET /api/audio-watermark/config?roomId=…&sampleRate=…`

Returns (creating on first call) the caller's embedding parameters for that meeting.
Returns 404 if convo-backend doesn't list the caller as a participant of `roomId`.
A `userId` parameter is still accepted for older clients, but it must match the
token (`403` otherwise).

`sampleRate` is the embedder AudioContext's rate (e.g. `48000` or `44100`).
The watermark's frame grid is defined in samples at that rate, so detection
resamples each recording to every participant's stored rate before searching.
It is optional for backward compatibility. Configs without one assume the
recording is already at the embedder's rate.

```json
{ "seed": "3F9A1C", "alpha": 4.0, "frameSize": 256, "analysisWindowSize": 512,
  "numBands": 24, "cycleSeconds": 4.0 }
```

### `POST /api/audio-watermark/detect` (multipart/form-data)

| Part | Meaning |
|---|---|
| `audio` | The recording (WAV, MP3, AAC, M4A, Opus, … up to 50 MB) |
| `sessionId` | The meeting code to check participants of |

Returns the best-matching participant, if any, plus every participant's score.
A detection needs the best score to be at least `0.015` **and** at least `0.01`
ahead of the runner-up (see `WatermarkDetectionService`). Both thresholds are
still placeholders, not yet calibrated against a real corpus.

The exhaustive fallback is CPU-heavy: expect tens of seconds per registered
participant on a small instance.

---

## Configuration

Read from the environment, or from `audio-watermark/.env` for local runs (gitignored):

| Variable | Purpose |
|---|---|
| `DB_URL`, `DB_USER`, `DB_PASS` | Postgres (shared Supabase instance; this service owns only `watermark_config`) |
| `CONVO_BACKEND_URL` | Base URL of convo-backend, for the internal participants API |
| `INTERNAL_SERVICE_KEY` | Must match convo-backend's `app.internal.service-key` |
| `JWT_SECRET` | Must match convo-backend's `JWT_SECRET` (at least 32 bytes). No default: the service won't start without it |
| `PORT` | HTTP port (default `8081`) |

---

## Running locally

Requirements: Java 21+, and **ffmpeg on the `PATH`**. The service refuses to start without it.

```bash
cd audio-watermark
./mvnw spring-boot:run      # Windows: mvnw.cmd spring-boot:run
./mvnw test                 # uses in-memory H2; no DB or convo-backend needed
```

The service listens on <http://localhost:8081>.

## Deployment

`Dockerfile` (repo root) builds the jar and installs ffmpeg in the runtime image.
On every push to `main` that touches `audio-watermark/**`, GitHub Actions
(`.github/workflows/ci.yml`) runs the tests, then triggers the Render deploy hook.
