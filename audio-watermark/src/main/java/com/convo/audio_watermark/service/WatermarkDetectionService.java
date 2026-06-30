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
 * 1. Look up every user registered in the session from the database.
 * 2. Decode the uploaded audio to normalised float PCM samples.
 * 3. For each user in the session:
 *    a. Create a streaming PRNG from their seed (same as the embedder).
 *    b. Process hop-aligned frames using the same analysis window size:
 *       - Window the audio with a Hann window.
 *       - FFT the windowed audio.
 *       - Generate analysisSize fresh PN values from the streaming PRNG
 *         (consuming the same count the embedder did for that frame).
 *       - FFT the PN.
 *       - Compute spectral correlation between audio and PN spectra.
 *    c. Average the per-frame spectral correlations.
 * 4. The user with the highest average correlation is the best candidate.
 * 5. If that score exceeds a detection threshold the watermark is declared detected.
 *
 * PN generation exactly mirrors JS generatePN() in the audio worklet:
 * - Non-numeric string seed → djb2 hashString() → mulberry32
 * - Numeric string seed → raw integer → mulberry32
 * - Chips: rand() * 2 − 1 (continuous float, not binary ±1)
 *
 * FFT, Hann window, and Bark-band mapping are ported from the JS worklet.
 */
@Service
public class WatermarkDetectionService {

    /** Minimum correlation to declare detection (empirically tuned). */
    private static final double DETECTION_THRESHOLD = 0.005;

    @Autowired
    private WatermarkConfigRepository repository;

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    public WatermarkDetectionResponse detect(MultipartFile audioFile, String sessionId)
            throws IOException, UnsupportedAudioFileException {

        // ── 1. Fetch all registered watermark configs for this meeting ───────
        List<WatermarkConfigRepository.DetectionConfigProjection> sessionConfigs = repository
                .findDetectionConfigsByMeetingCode(sessionId);
        if (sessionConfigs.isEmpty()) {
            return new WatermarkDetectionResponse(
                    null, null, sessionId, 0.0, false, 0, 0,
                    Collections.emptyMap(),
                    Collections.emptyMap(),
                    "No registered users found for watermark detection.");
        }

        // ── 2. Decode audio to float samples & extract sample rate ──────────
        DecodedAudio decoded = decodeAudioToFloatSamples(audioFile);
        float[] samples = decoded.samples;
        float sampleRate = decoded.sampleRate;

        if (samples.length == 0) {
            return new WatermarkDetectionResponse(
                    null, null, sessionId, 0.0, false, 0, sessionConfigs.size(),
                    Collections.emptyMap(),
                    Collections.emptyMap(),
                    "Audio file is empty or could not be decoded.");
        }

        // All users in the same session share the same frameSize / analysisWindowSize / numBands
        int frameSize = sessionConfigs.get(0).getFrameSize();
        int analysisSize = sessionConfigs.get(0).getAnalysisWindowSize() != null
                ? sessionConfigs.get(0).getAnalysisWindowSize() : frameSize * 2;
        int numBands = sessionConfigs.get(0).getNumBands() != null
                ? sessionConfigs.get(0).getNumBands() : 24;

        // Precompute shared resources
        float[] window = hannWindow(analysisSize);
        int[] binToBand = buildBinToBandMap(analysisSize, sampleRate, numBands);

        // Number of hop-aligned frames
        int hop = frameSize;
        int numFrames = 0;
        for (int off = 0; off + analysisSize <= samples.length; off += hop) {
            numFrames++;
        }

        if (numFrames == 0) {
            return new WatermarkDetectionResponse(
                    null, null, sessionId, 0.0, false, 0, sessionConfigs.size(),
                    Collections.emptyMap(),
                    Collections.emptyMap(),
                    "Audio too short for detection (need at least " + analysisSize + " samples).");
        }

        // ── 3. Score each user using spectral correlation ───────────────────
        Map<String, Double> allUserScores = new LinkedHashMap<>();
        Map<String, String> userDisplayNames = new LinkedHashMap<>();

        for (WatermarkConfigRepository.DetectionConfigProjection config : sessionConfigs) {
            double score = computeSpectralCorrelation(
                    samples, config.getSeed(), hop, analysisSize, numBands,
                    sampleRate, window, binToBand, config.getAlpha());
            allUserScores.put(config.getUserId(), round4(score));
            userDisplayNames.put(config.getUserId(), config.getDisplayName());
        }

        // ── 4. Find the user with the highest score ─────────────────────────
        String bestUserId = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (Map.Entry<String, Double> entry : allUserScores.entrySet()) {
            if (entry.getValue() > bestScore) {
                bestScore = entry.getValue();
                bestUserId = entry.getKey();
            }
        }

        // ── 5. Apply detection threshold ────────────────────────────────────
        boolean detected = bestScore >= DETECTION_THRESHOLD;

        final String finalBestUser = bestUserId;
        WatermarkConfigRepository.DetectionConfigProjection winnerConfig = sessionConfigs.stream()
                .filter(c -> c.getUserId().equals(finalBestUser))
                .findFirst()
                .orElse(sessionConfigs.get(0));

        String message = detected
                ? String.format(
                        "Watermark detected. Detected user: '%s' | score=%.6f | threshold=%.6f",
                        winnerConfig.getDisplayName(), bestScore, DETECTION_THRESHOLD)
                : String.format(
                        "No watermark detected. Highest score: %.6f for user '%s' | threshold=%.6f",
                        bestScore, winnerConfig.getDisplayName(), DETECTION_THRESHOLD);

        return new WatermarkDetectionResponse(
                detected ? winnerConfig.getUserId() : null,
                detected ? winnerConfig.getDisplayName() : null,
                sessionId,
                round4(bestScore),
                detected,
                numFrames,
                sessionConfigs.size(),
                allUserScores,
                userDisplayNames,
                message);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Spectral correlation — mirrors the embedder pipeline
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Compute the average spectral correlation between the audio and the
     * watermark that would have been embedded by the given seed.
     *
     * This mirrors the embedder exactly:
     * - A streaming PRNG is created from the seed.
     * - For each hop-aligned frame, analysisSize PN values are consumed
     *   (same as the embedder), FFTed, and correlated against the audio spectrum.
     */
    private double computeSpectralCorrelation(
            float[] samples, String seed, int hop, int analysisSize,
            int numBands, float sampleRate, float[] window, int[] binToBand, double alpha) {

        // Create a streaming PRNG — same initial state as the embedder
        long[] prngState = { resolveSeed(seed) };

        // The embedder's margin in linear amplitude
        double marginLinear = Math.pow(10.0, alpha / 20.0);

        // Scratch buffers
        double[] audioRe = new double[analysisSize];
        double[] audioIm = new double[analysisSize];
        double[] pnRe = new double[analysisSize];
        double[] pnIm = new double[analysisSize];
        double[] bandEnergy = new double[numBands];
        double[] bandLogSum = new double[numBands];
        double[] bandCount = new double[numBands];
        double[] bandFlatness = new double[numBands];
        double[] bandThreshold = new double[numBands];

        double totalCorrelation = 0.0;
        int numFrames = 0;

        for (int off = 0; off + analysisSize <= samples.length; off += hop) {
            // ── 1. Window the audio ─────────────────────────────────────────
            for (int i = 0; i < analysisSize; i++) {
                audioRe[i] = samples[off + i] * window[i];
                audioIm[i] = 0.0;
            }
            fft(audioRe, audioIm, false);

            // ── 2. Bark-band energy + spectral flatness ─────────────────────
            Arrays.fill(bandEnergy, 0.0);
            Arrays.fill(bandLogSum, 0.0);
            Arrays.fill(bandCount, 0.0);
            for (int k = 0; k < analysisSize / 2; k++) {
                double mag2 = audioRe[k] * audioRe[k] + audioIm[k] * audioIm[k];
                int b = binToBand[k];
                bandEnergy[b] += mag2;
                bandLogSum[b] += Math.log(mag2 + 1e-12);
                bandCount[b] += 1.0;
            }
            for (int b = 0; b < numBands; b++) {
                double count = Math.max(bandCount[b], 1.0);
                double geoMean = Math.exp(bandLogSum[b] / count);
                double arithMean = bandEnergy[b] / count;
                bandFlatness[b] = geoMean / (arithMean + 1e-12);
                bandEnergy[b] = arithMean;
            }

            // ── 3. Masking threshold per band ───────────────────────────────
            for (int b = 0; b < numBands; b++) {
                double flat = Math.min(Math.max(bandFlatness[b], 0.0), 1.0);
                double offsetDb = 18.0 - flat * 12.0;
                double energyDb = 10.0 * Math.log10(bandEnergy[b] + 1e-12);
                bandThreshold[b] = Math.pow(10.0, (energyDb - offsetDb) / 10.0);
            }
            // 3-tap spreading
            double[] spreadThreshold = Arrays.copyOf(bandThreshold, numBands);
            for (int b = 0; b < numBands; b++) {
                double left = b > 0 ? bandThreshold[b - 1] : bandThreshold[b];
                double right = b < numBands - 1 ? bandThreshold[b + 1] : bandThreshold[b];
                spreadThreshold[b] = 0.5 * bandThreshold[b] + 0.25 * left + 0.25 * right;
            }

            // ── 4. Generate this frame's PN spectrum (streaming PRNG) ───────
            for (int k = 0; k < analysisSize; k++) {
                pnRe[k] = mulberry32Next(prngState) * 2.0 - 1.0;
                pnIm[k] = 0.0;
            }
            fft(pnRe, pnIm, false);

            // ── 5. Shape PN spectrum by sqrt(threshold) * margin ────────────
            for (int k = 0; k < analysisSize / 2; k++) {
                int b = binToBand[k];
                double mag = Math.sqrt(pnRe[k] * pnRe[k] + pnIm[k] * pnIm[k]) + 1e-12;
                double gain = (Math.sqrt(spreadThreshold[b]) * marginLinear) / mag;
                pnRe[k] *= gain;
                pnIm[k] *= gain;
                int mirror = (analysisSize - k) % analysisSize;
                pnRe[mirror] = pnRe[k];
                pnIm[mirror] = -pnIm[k];
            }

            // ── 6. Spectral correlation ─────────────────────────────────────
            // Correlation = Σ Re(audio) * Re(pn) + Im(audio) * Im(pn)
            // over positive frequencies, normalised
            double corr = 0.0;
            double audioPower = 0.0;
            double pnPower = 0.0;
            for (int k = 1; k < analysisSize / 2; k++) {
                corr += audioRe[k] * pnRe[k] + audioIm[k] * pnIm[k];
                audioPower += audioRe[k] * audioRe[k] + audioIm[k] * audioIm[k];
                pnPower += pnRe[k] * pnRe[k] + pnIm[k] * pnIm[k];
            }
            // Normalised correlation coefficient
            double denom = Math.sqrt(audioPower * pnPower);
            double normCorr = denom > 1e-12 ? corr / denom : 0.0;

            totalCorrelation += normCorr;
            numFrames++;
        }

        return numFrames > 0 ? totalCorrelation / numFrames : 0.0;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // FFT — exact port of the JS worklet's radix-2 iterative FFT
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * In-place iterative radix-2 FFT (or inverse FFT when invert=true).
     * Mirrors the JS _fft(re, im, invert) in audio-processor.worklet.js.
     */
    private static void fft(double[] re, double[] im, boolean invert) {
        int n = re.length;
        // Bit-reversal permutation
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) {
                j ^= bit;
            }
            j ^= bit;
            if (i < j) {
                double tmp = re[i]; re[i] = re[j]; re[j] = tmp;
                tmp = im[i]; im[i] = im[j]; im[j] = tmp;
            }
        }
        // Butterfly passes
        for (int len = 2; len <= n; len <<= 1) {
            double ang = (2.0 * Math.PI / len) * (invert ? -1 : 1);
            double wr = Math.cos(ang);
            double wi = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double curWr = 1.0, curWi = 0.0;
                for (int j = 0; j < len / 2; j++) {
                    double ur = re[i + j], ui = im[i + j];
                    double vr = re[i + j + len / 2] * curWr - im[i + j + len / 2] * curWi;
                    double vi = re[i + j + len / 2] * curWi + im[i + j + len / 2] * curWr;
                    re[i + j] = ur + vr;
                    im[i + j] = ui + vi;
                    re[i + j + len / 2] = ur - vr;
                    im[i + j + len / 2] = ui - vi;
                    double nWr = curWr * wr - curWi * wi;
                    double nWi = curWr * wi + curWi * wr;
                    curWr = nWr;
                    curWi = nWi;
                }
            }
        }
        if (invert) {
            for (int i = 0; i < n; i++) {
                re[i] /= n;
                im[i] /= n;
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Hann window — mirrors JS _hannWindow(n)
    // ─────────────────────────────────────────────────────────────────────────

    private static float[] hannWindow(int n) {
        float[] w = new float[n];
        for (int i = 0; i < n; i++) {
            w[i] = (float) (0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (n - 1)));
        }
        return w;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Bark-band mapping — mirrors JS _hzToBark and _buildBinToBandMap
    // ─────────────────────────────────────────────────────────────────────────

    private static double hzToBark(double hz) {
        return 13.0 * Math.atan(0.00076 * hz) + 3.5 * Math.atan(Math.pow(hz / 7500.0, 2));
    }

    private static int[] buildBinToBandMap(int n, float sampleRate, int numBands) {
        int[] map = new int[n];
        double nyquistBark = hzToBark(sampleRate / 2.0);
        for (int k = 0; k < n; k++) {
            double hz = (double) k * sampleRate / n;
            double bark = hzToBark(hz);
            int band = (int) Math.floor((bark / nyquistBark) * numBands);
            if (band >= numBands) band = numBands - 1;
            if (band < 0) band = 0;
            map[k] = band;
        }
        return map;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Audio decoding — reads sample rate from the WAV header
    // ─────────────────────────────────────────────────────────────────────────

    /** Holds decoded audio samples and the source sample rate. */
    private static class DecodedAudio {
        final float[] samples;
        final float sampleRate;
        DecodedAudio(float[] samples, float sampleRate) {
            this.samples = samples;
            this.sampleRate = sampleRate;
        }
    }

    /**
     * Decode the uploaded audio file to normalised float32 samples in [-1, +1].
     * Also extracts the sample rate from the audio header.
     */
    private DecodedAudio decodeAudioToFloatSamples(MultipartFile audioFile)
            throws IOException, UnsupportedAudioFileException {

        byte[] fileBytes = audioFile.getBytes();

        try (AudioInputStream ais = AudioSystem.getAudioInputStream(
                new BufferedInputStream(new ByteArrayInputStream(fileBytes)))) {

            AudioFormat src = ais.getFormat();
            float sampleRate = src.getSampleRate();

            // Target: 16-bit signed mono little-endian PCM
            AudioFormat pcmFormat = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    sampleRate,
                    16,
                    1,       // mono
                    2,       // 2 bytes per frame
                    sampleRate,
                    false);  // little-endian

            try (AudioInputStream pcm = AudioSystem.getAudioInputStream(pcmFormat, ais)) {
                return new DecodedAudio(pcmBytesToFloats(pcm.readAllBytes()), sampleRate);
            }

        } catch (UnsupportedAudioFileException e) {
            // Fallback: assume raw 16-bit PCM at 48000 Hz
            return new DecodedAudio(pcmBytesToFloats(fileBytes), 48000f);
        }
    }

    /**
     * Convert raw 16-bit little-endian signed PCM bytes to float32 in [-1, +1].
     */
    private float[] pcmBytesToFloats(byte[] bytes) {
        int n = bytes.length / 2;
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            short s = (short) ((bytes[2 * i + 1] << 8) | (bytes[2 * i] & 0xFF));
            out[i] = s / 32768.0f;
        }
        return out;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PN generation — exact Java port of the front-end JS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Resolve a seed string to an unsigned 32-bit long, exactly as JS does:
     * numeric string → parseInt(seed) & 0xFFFFFFFF (mirrors seed >>> 0)
     * other string   → djb2 hashString(seed)
     */
    private long resolveSeed(String seed) {
        try {
            long v = Long.parseLong(seed);
            if (v >= 0 && v <= 0xFFFFFFFFL)
                return v & 0xFFFFFFFFL;
        } catch (NumberFormatException ignored) {
        }
        return hashString(seed);
    }

    private long hashString(String str) {
        long hash = 5381L;
        for (int i = 0; i < str.length(); i++)
            hash = ((((hash & 0xFFFFFFFFL) << 5) + hash) + str.charAt(i)) & 0xFFFFFFFFL;
        return hash;
    }

    /**
     * One step of mulberry32 — exact port of JS mulberry32().
     * All values kept as unsigned 32-bit via masking with 0xFFFFFFFFL.
     */
    private double mulberry32Next(long[] state) {
        state[0] = (state[0] + 0x6d2b79f5L) & 0xFFFFFFFFL;
        long s = state[0];
        long t = imul32(s ^ (s >>> 15), 1L | s);
        t = (t + imul32(t ^ (t >>> 7), 61L | t)) ^ t;
        t = (t ^ (t >>> 14)) & 0xFFFFFFFFL;
        return t / 4294967296.0;
    }

    /**
     * Unsigned 32-bit multiply — mirrors JS Math.imul().
     */
    private long imul32(long a, long b) {
        return (a & 0xFFFFFFFFL) * (b & 0xFFFFFFFFL) & 0xFFFFFFFFL;
    }

    private double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}