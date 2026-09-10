package com.example.ahakey.service;

import com.k2fsa.sherpa.onnx.WaveReader;
import com.example.ahakey.service.speech.RollingPreview;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SpeechServiceTest {

    @Test
    void realOfficialAudioProducesPreviewBeforeFinalWhenProvided() throws Exception {
        Path model = optionalPath("ahakey.test.sensevoice.model");
        Path tokens = optionalPath("ahakey.test.sensevoice.tokens");
        Path wave = optionalPath("ahakey.test.sensevoice.wav");
        SpeechService service = new SpeechService();
        try {
            service.initialize(model.toString(), tokens.toString());
            WaveReader reader = new WaveReader(wave.toString());
            float[] samples = reader.getSamples();
            byte[] pcm = new byte[samples.length * 2];
            for (int i = 0; i < samples.length; i++) {
                short value = (short) Math.round(samples[i] * 32768.0f);
                pcm[i * 2] = (byte) value;
                pcm[i * 2 + 1] = (byte) (value >> 8);
            }
            java.util.concurrent.CountDownLatch previewSeen = new java.util.concurrent.CountDownLatch(1);
            java.util.List<String> finals = new java.util.ArrayList<>();
            try (RollingPreview preview = new RollingPreview(reader.getSampleRate(), service::recognize,
                text -> { if (!text.isBlank()) previewSeen.countDown(); })) {
                preview.append(pcm, pcm.length);
                assertTrue(previewSeen.await(30, java.util.concurrent.TimeUnit.SECONDS),
                    "Provisional transcript must arrive before release/final decode");
                preview.finish(finals::add);
                assertEquals(java.util.List.of("开饭时间早上9点至下午5点。"), finals);
            }
        } finally { service.release(); }
    }

    @Test
    void convertsSignedLittleEndianPcmToNormalizedFloats() {
        byte[] pcm = {
            0x00, (byte) 0x80,
            0x00, 0x00,
            (byte) 0xff, 0x7f
        };

        assertArrayEquals(
            new float[] {-1.0f, 0.0f, 32767.0f / 32768.0f},
            SpeechService.pcm16LittleEndianToFloat(pcm)
        );
    }

    @Test
    void pinnedOfficialInt8BundleMatchesItsWindowsKnownAnswerWhenProvided() throws Exception {
        Path model = optionalPath("ahakey.test.sensevoice.model");
        Path tokens = optionalPath("ahakey.test.sensevoice.tokens");
        Path wave = optionalPath("ahakey.test.sensevoice.wav");

        SpeechService service = new SpeechService();
        try {
            service.initialize(model.toString(), tokens.toString());
            WaveReader reader = new WaveReader(wave.toString());
            // This exact official int8 asset deterministically recognizes the
            // source utterance's 开放 as 开饭 with sherpa-onnx v1.13.7 on
            // Windows. Pin the observed artifact/runtime answer here so the
            // probe detects missing, mismatched, or silently broken native
            // dependencies without disguising this quantization limitation.
            assertEquals(
                "开饭时间早上9点至下午5点。",
                service.recognize(reader.getSamples(), reader.getSampleRate())
            );
        } finally {
            service.release();
        }
    }

    private static Path optionalPath(String propertyName) {
        String value = System.getProperty(propertyName);
        assumeTrue(value != null && !value.isBlank(), propertyName + " is not set");
        Path path = Path.of(value).toAbsolutePath().normalize();
        assumeTrue(Files.isRegularFile(path), propertyName + " does not name a file");
        return path;
    }
}
