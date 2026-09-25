package com.convo.audio_watermark.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Decodes uploaded audio files to mono float PCM samples for detection.
 * Tries {@link AudioSystem} first (WAV/AIFF/AU, no external dependency),
 * falling back to an ffmpeg transcode for everything else (MP3, AAC, M4A,
 * Opus, ...).
 */
@Component
class WatermarkAudioDecoder {

    private static final Logger log = LoggerFactory.getLogger(WatermarkAudioDecoder.class);

    /** Sample rate ffmpeg decodes to when no specific rate is requested (see {@link #decode}). */
    private static final int DEFAULT_SAMPLE_RATE = 48000;

    /** Max time to let a single ffmpeg transcode run before giving up. */
    private static final long FFMPEG_TIMEOUT_SECONDS = 60;

    static final class DecodedAudio {
        final float[] samples;
        final float sampleRate;
        DecodedAudio(float[] samples, float sampleRate) {
            this.samples = samples;
            this.sampleRate = sampleRate;
        }
    }

    /**
     * Fail fast at startup if ffmpeg isn't available in this environment,
     * rather than discovering it silently on the first non-WAV upload.
     */
    @PostConstruct
    public void verifyFfmpegAvailable() {
        try {
            Process p = new ProcessBuilder("ffmpeg", "-version").start();
            boolean finished = p.waitFor(10, TimeUnit.SECONDS);
            if (!finished || p.exitValue() != 0) {
                throw new IllegalStateException("ffmpeg check failed or timed out");
            }
        } catch (Exception e) {
            throw new IllegalStateException(
                    "ffmpeg is not available on this system — audio decoding for non-WAV formats "
                            + "(MP3/AAC/M4A/Opus) will fail. Ensure ffmpeg is installed in the "
                            + "deployment environment (see Dockerfile).", e);
        }
    }

    /**
     * Decodes at the file's own rate for WAV/AIFF/AU, or at
     * {@link #DEFAULT_SAMPLE_RATE} for everything that has to go through ffmpeg.
     */
    DecodedAudio decode(byte[] fileBytes, String originalFilename)
            throws IOException, UnsupportedAudioFileException {

        long startNanos = System.nanoTime();
        try {
            DecodedAudio result = decodeUsingAudioSystem(fileBytes);
            log.info("watermark-decode: AudioSystem (WAV/AIFF/AU) path took {} ms",
                    (System.nanoTime() - startNanos) / 1_000_000);
            return result;
        } catch (UnsupportedAudioFileException e) {
            DecodedAudio result = decodeUsingFfmpeg(fileBytes, originalFilename, DEFAULT_SAMPLE_RATE);
            log.info("watermark-decode: ffmpeg (MP3/AAC/M4A/Opus) path took {} ms",
                    (System.nanoTime() - startNanos) / 1_000_000);
            return result;
        }
    }

    /**
     * Decodes any supported format resampled to exactly
     * {@code targetSampleRate}, via ffmpeg. Used when the rate a watermark
     * was embedded at differs from what {@link #decode} produced: the
     * embedder's frame grid is defined in samples at ITS rate, so the
     * recording must be brought back to that rate before searching.
     */
    DecodedAudio decodeAtRate(byte[] fileBytes, String originalFilename, int targetSampleRate)
            throws IOException {

        long startNanos = System.nanoTime();
        DecodedAudio result = decodeUsingFfmpeg(fileBytes, originalFilename, targetSampleRate);
        log.info("watermark-decode: ffmpeg resample to {} Hz took {} ms",
                targetSampleRate, (System.nanoTime() - startNanos) / 1_000_000);
        return result;
    }

    private DecodedAudio decodeUsingAudioSystem(byte[] fileBytes)
            throws IOException, UnsupportedAudioFileException {

        try (AudioInputStream ais = AudioSystem.getAudioInputStream(
                new BufferedInputStream(new ByteArrayInputStream(fileBytes)))) {

            AudioFormat src = ais.getFormat();
            float sampleRate = src.getSampleRate();

            AudioFormat pcmFormat = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    sampleRate,
                    16,
                    1,
                    2,
                    sampleRate,
                    false);

            try (AudioInputStream pcm = AudioSystem.getAudioInputStream(pcmFormat, ais)) {
                return new DecodedAudio(pcmBytesToFloats(pcm.readAllBytes()), sampleRate);
            }
        }
    }

    private DecodedAudio decodeUsingFfmpeg(byte[] fileBytes, String originalFilename, int targetSampleRate)
            throws IOException {

        Path inputPath = Files.createTempFile("watermark-in-", extractExtension(originalFilename));
        Path outputPath = Files.createTempFile("watermark-out-", ".wav");

        try {
            Files.write(inputPath, fileBytes);

            List<String> command = List.of(
                    "ffmpeg",
                    "-y",
                    "-i", inputPath.toString(),
                    "-ac", "1",
                    "-ar", String.valueOf(targetSampleRate),
                    "-f", "wav",
                    outputPath.toString());

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            String ffmpegOutput;
            try (InputStream is = process.getInputStream()) {
                ffmpegOutput = new String(is.readAllBytes());
            }

            boolean finished;
            try {
                finished = process.waitFor(FFMPEG_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for ffmpeg", ie);
            }

            if (!finished) {
                process.destroyForcibly();
                throw new IOException("ffmpeg timed out while decoding audio");
            }
            if (process.exitValue() != 0) {
                throw new IOException("ffmpeg failed to decode audio (exit=" + process.exitValue()
                        + "): " + ffmpegOutput);
            }

            byte[] wavBytes = Files.readAllBytes(outputPath);

            try (AudioInputStream ais = AudioSystem.getAudioInputStream(
                    new BufferedInputStream(new ByteArrayInputStream(wavBytes)))) {

                AudioFormat src = ais.getFormat();
                float sampleRate = src.getSampleRate();

                AudioFormat pcmFormat = new AudioFormat(
                        AudioFormat.Encoding.PCM_SIGNED,
                        sampleRate, 16, 1, 2, sampleRate, false);

                try (AudioInputStream pcm = AudioSystem.getAudioInputStream(pcmFormat, ais)) {
                    return new DecodedAudio(pcmBytesToFloats(pcm.readAllBytes()), sampleRate);
                }
            } catch (UnsupportedAudioFileException uafe) {
                throw new IOException("ffmpeg produced a WAV file that could not be read back", uafe);
            }

        } finally {
            Files.deleteIfExists(inputPath);
            Files.deleteIfExists(outputPath);
        }
    }

    private String extractExtension(String originalFilename) {
        if (originalFilename == null) return ".tmp";
        int dot = originalFilename.lastIndexOf('.');
        if (dot < 0 || dot == originalFilename.length() - 1) return ".tmp";
        return originalFilename.substring(dot);
    }

    private float[] pcmBytesToFloats(byte[] bytes) {
        int n = bytes.length / 2;
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            short s = (short) ((bytes[2 * i + 1] << 8) | (bytes[2 * i] & 0xFF));
            out[i] = s / 32768.0f;
        }
        return out;
    }
}