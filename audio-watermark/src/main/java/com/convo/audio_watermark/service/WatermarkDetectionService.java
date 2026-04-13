package com.convo.audio_watermark.service;

import com.convo.audio_watermark.dto.WatermarkDetectionResponse;
import com.convo.audio_watermark.repository.WatermarkConfigRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.sound.sampled.*;
import java.io.*;
import java.util.*;

/**
 * Detects audio watermarks embedded by the front-end audio worklet.
 *
 * Detection pipeline (mirrors the front-end embedder exactly):
 *
 *  1. Look up every user registered in the session from the database.
 *  2. Decode the uploaded audio to normalised float PCM samples.
 *  3. Segment the samples into frames of frameSize (same as embedding).
 *  4. For each user in the session:
 *       a. Regenerate their PN sequence from their DB seed using the
 *          same PRNG as the front-end (mulberry32 + hashString/numeric).
 *       b. Compute the average raw dot-product correlation across all frames.
 *  5. The user with the highest correlation score is the best candidate.
 *  6. If that score exceeds  alpha * frameSize * pnPower * 0.5  the
 *     watermark is declared detected.
 *
 * PN generation exactly mirrors JS generatePN() in the audio worklet:
 *   - Non-numeric string seed  →  djb2 hashString()  →  mulberry32
 *   - Numeric string seed      →  raw integer         →  mulberry32
 *   - Chips: rand() * 2 − 1   (continuous float, not binary ±1)
 *
 * Correlation mirrors JS correlate():
 *   corr = Σ frame[i] * pn[i]   (raw dot product, no normalisation)
 */
@Service
public class WatermarkDetectionService {

    /** Threshold multiplier: alpha * frameSize * pnPower * THRESHOLD_FACTOR */
    private static final double THRESHOLD_FACTOR = 0.3;

    @Autowired
    private WatermarkConfigRepository repository;

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    public WatermarkDetectionResponse detect(MultipartFile audioFile, String sessionId)
            throws IOException, UnsupportedAudioFileException {

        // ── 1. Fetch all registered watermark configs for this meeting ───────
        List<WatermarkConfigRepository.DetectionConfigProjection> sessionConfigs =
            repository.findDetectionConfigsByMeetingCode(sessionId);
        if (sessionConfigs.isEmpty()) {
            return new WatermarkDetectionResponse(
                    null, sessionId, 0.0, false, 0, 0,
                    Collections.emptyMap(),
                "No registered users found for watermark detection.");
        }

        // ── 2. Decode audio to float samples ───────────────────────────────────
        float[] samples = decodeAudioToFloatSamples(audioFile);
        if (samples.length == 0) {
            return new WatermarkDetectionResponse(
                    null, sessionId, 0.0, false, 0, sessionConfigs.size(),
                    Collections.emptyMap(),
                    "Audio file is empty or could not be decoded.");
        }

        // All users in the same session share the same frameSize
        int frameSize = sessionConfigs.get(0).getFrameSize();

        // ── 3. Segment into non-overlapping frames ─────────────────────────────
        List<float[]> frames = segmentIntoFrames(samples, frameSize);

        // ── 4. Score each user using their DB seed and alpha ───────────────────
        Map<String, Double> allUserScores = new LinkedHashMap<>();

        for (WatermarkConfigRepository.DetectionConfigProjection config : sessionConfigs) {
            double score = computeAverageCorrelation(frames, config.getSeed(), frameSize);
            allUserScores.put(config.getUserId(), round4(score));
        }

        // ── 5. Find the user with the highest score ────────────────────────────
        String bestUser  = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (Map.Entry<String, Double> entry : allUserScores.entrySet()) {
            if (entry.getValue() > bestScore) {
                bestScore = entry.getValue();
                bestUser  = entry.getKey();
            }
        }

        // ── 6. Compute threshold for the winning user using their DB config ────
        //       threshold = alpha * frameSize * pnPower * 0.5
        //       This mirrors JS: alpha * FRAME * pnPower * 0.5
        // ── 6. Compute threshold for the winning user using their DB config ────
        final String finalBestUser = bestUser;

        WatermarkConfigRepository.DetectionConfigProjection winnerConfig = sessionConfigs.stream()
            .filter(c -> c.getUserId().equals(finalBestUser))
            .findFirst()
            .orElse(sessionConfigs.get(0));

        float[] winnerPN = generatePN(winnerConfig.getSeed(), frameSize);
        double pnPower   = computePnPower(winnerPN);
        double threshold = winnerConfig.getAlpha() * frameSize * pnPower * THRESHOLD_FACTOR;

        boolean detected = bestScore >= threshold;

        String message = detected
                ? String.format(
                        "Watermark detected. Detected user: '%s' | score=%.4f | threshold=%.4f",
                        bestUser, bestScore, threshold)
                : String.format(
                        "No watermark detected. Highest score: %.4f for user '%s' | threshold=%.4f",
                        bestScore, bestUser, threshold);

        return new WatermarkDetectionResponse(
                detected ? bestUser : null,
                sessionId,
                round4(bestScore),
                detected,
                frames.size(),
                sessionConfigs.size(),
                allUserScores,
                message);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Audio decoding
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Decode the uploaded audio file to normalised float32 samples in [-1, +1].
     * Requests little-endian 16-bit mono PCM so that pcmBytesToFloats()
     * can read the bytes directly without any byte-swap.
     */
    private float[] decodeAudioToFloatSamples(MultipartFile audioFile)
            throws IOException, UnsupportedAudioFileException {

        byte[] fileBytes = audioFile.getBytes();

        try (AudioInputStream ais = AudioSystem.getAudioInputStream(
                new BufferedInputStream(new ByteArrayInputStream(fileBytes)))) {

            AudioFormat src = ais.getFormat();

            // Target: 16-bit signed mono little-endian PCM
            // bigEndian = true  means BIG-endian in Java AudioFormat
            // bigEndian = false means LITTLE-endian — matches pcmBytesToFloats()
            AudioFormat pcmFormat = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    src.getSampleRate(),
                    16,
                    1,                   // mono
                    2,                   // 2 bytes per frame
                    src.getSampleRate(),
                    false);              // little-endian

            try (AudioInputStream pcm = AudioSystem.getAudioInputStream(pcmFormat, ais)) {
                return pcmBytesToFloats(pcm.readAllBytes());
            }

        } catch (UnsupportedAudioFileException e) {
            // Fallback for containers AudioSystem cannot open natively
            return pcmBytesToFloats(fileBytes);
        }
    }

    /**
     * Convert raw 16-bit little-endian signed PCM bytes to float32 in [-1, +1].
     * Matches the inverse of JS encodeWAV():
     *   positive sample: encoded as s * 0x7FFF, decoded as val / 32768
     *   negative sample: encoded as s * 0x8000, decoded as val / 32768
     */
    private float[] pcmBytesToFloats(byte[] bytes) {
        int n = bytes.length / 2;
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            // Little-endian: low byte first, high byte second
            short s = (short) ((bytes[2 * i + 1] << 8) | (bytes[2 * i] & 0xFF));
            out[i] = s / 32768.0f;
        }
        return out;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Framing
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Slice samples into non-overlapping frames of frameSize.
     * Trailing samples that don't fill a complete frame are discarded,
     * matching the worklet's ring-buffer behaviour.
     */
    private List<float[]> segmentIntoFrames(float[] samples, int frameSize) {
        List<float[]> frames = new ArrayList<>();
        for (int off = 0; off + frameSize <= samples.length; off += frameSize)
            frames.add(Arrays.copyOfRange(samples, off, off + frameSize));
        return frames;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Correlation — mirrors JS correlate(frame, pn) = Σ frame[i] * pn[i]
    // ─────────────────────────────────────────────────────────────────────────

    private double computeAverageCorrelation(List<float[]> frames, String seed, int frameSize) {
        float[] pn    = generatePN(seed, frameSize);
        double  total = 0.0;
        for (float[] frame : frames) {
            double c = 0.0;
            for (int i = 0; i < frameSize; i++) c += frame[i] * pn[i];
            total += c;
        }
        return frames.isEmpty() ? 0.0 : total / frames.size();
    }

    private double computePnPower(float[] pn) {
        double sum = 0.0;
        for (float v : pn) sum += (double) v * v;
        return sum / pn.length;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PN generation — exact Java port of the front-end JS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Mirrors JS generatePN(seed, length):
     *   if (typeof seed === "string") numericSeed = hashString(seed)
     *   else                          numericSeed = seed >>> 0
     *   chips[i] = rand() * 2 − 1
     *
     * The seed stored in the DB is always a String (e.g. "A7F3K9").
     * Non-numeric strings go through djb2 hashString().
     * Purely numeric strings (e.g. "42") are treated as raw unsigned 32-bit
     * integers, matching the JS `seed >>> 0` path for numeric seeds.
     */
    private float[] generatePN(String seed, int length) {
        long[] state = { resolveSeed(seed) };
        float[] pn   = new float[length];
        for (int i = 0; i < length; i++)
            pn[i] = (float) (mulberry32Next(state) * 2.0 - 1.0);
        return pn;
    }

    /**
     * Resolve a seed string to an unsigned 32-bit long, exactly as JS does:
     *   numeric string  →  parseInt(seed) & 0xFFFFFFFF  (mirrors seed >>> 0)
     *   other string    →  djb2 hashString(seed)
     */
    private long resolveSeed(String seed) {
        try {
            long v = Long.parseLong(seed);
            if (v >= 0 && v <= 0xFFFFFFFFL)
                return v & 0xFFFFFFFFL;
        } catch (NumberFormatException ignored) { }
        return hashString(seed);
    }

    private long hashString(String str) {
        long hash = 5381L;
        for (int i = 0; i < str.length(); i++)
            hash = ((((hash & 0xFFFFFFFFL) << 5) + hash) + str.charAt(i)) & 0xFFFFFFFFL;
        return hash;
    }

    /**
     * One step of mulberry32 — exact port of JS mulberry32():
     *   s += 0x6d2b79f5;
     *   let t = Math.imul(s ^ (s >>> 15), 1 | s);
     *   t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
     *   return ((t ^ (t >>> 14)) >>> 0) / 0x100000000;
     *
     * All values kept as unsigned 32-bit via masking with 0xFFFFFFFFL.
     */
    private double mulberry32Next(long[] state) {
        state[0] = (state[0] + 0x6d2b79f5L) & 0xFFFFFFFFL;
        long s   = state[0];
        long t   = imul32(s ^ (s >>> 15), 1L | s);
        t = (t + imul32(t ^ (t >>> 7), 61L | t)) ^ t;
        t = (t ^ (t >>> 14)) & 0xFFFFFFFFL;
        return t / 4294967296.0;
    }

    /**
     * Unsigned 32-bit multiply — mirrors JS Math.imul().
     * Keeps only the lower 32 bits of the product.
     */
    private long imul32(long a, long b) {
        return (a & 0xFFFFFFFFL) * (b & 0xFFFFFFFFL) & 0xFFFFFFFFL;
    }

    private double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}