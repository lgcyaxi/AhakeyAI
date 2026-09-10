package com.example.ahakey.service;

import com.example.ahakey.config.ModelConfig;
import com.example.ahakey.service.speech.RollingPreview;
import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult;
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.TargetDataLine;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Records 16 kHz mono PCM with rolling provisional offline re-decodes.
 * One final whole-utterance decode is performed after release.
 */
public class SpeechService {

    private static final Logger logger = LoggerFactory.getLogger(SpeechService.class);
    private static final int BITS_PER_SAMPLE = 16;
    private static final int BYTES_PER_SAMPLE = BITS_PER_SAMPLE / 8;
    private static final long RELEASE_JOIN_MILLIS = 10_000;

    private final Object lifecycleLock = new Object();
    private final Object recognizerLock = new Object();

    private OfflineRecognizer recognizer;
    private Thread recognitionThread;
    private volatile boolean captureRequested;
    private volatile TargetDataLine activeLine;
    private volatile RollingPreview activePreview;
    private volatile boolean cancelled;
    private volatile String lastError = "";
    private int sampleRate = 16_000;

    public interface Consumer<T> {
        void accept(T value);
    }

    public void initialize() throws Exception {
        ModelConfig config = ModelConfig.getInstance();
        initialize(config.getModelPath(), config.getTokensPath());
    }

    public void initialize(String modelPath, String tokensPath) throws Exception {
        Path model = requireRegularFile(modelPath, "SenseVoice model");
        Path tokens = requireRegularFile(tokensPath, "SenseVoice tokens");
        ModelConfig config = ModelConfig.getInstance();

        release();
        sampleRate = config.getSampleRate();

        OfflineSenseVoiceModelConfig.Builder senseVoiceBuilder =
            OfflineSenseVoiceModelConfig.builder()
                .setModel(model.toString())
                .setInverseTextNormalization(config.useInverseTextNormalization());
        String language = config.getLanguageCode();
        if (!language.isBlank() && !"auto".equalsIgnoreCase(language)) {
            senseVoiceBuilder.setLanguage(language);
        }

        OfflineModelConfig modelConfig = OfflineModelConfig.builder()
            .setSenseVoice(senseVoiceBuilder.build())
            .setTokens(tokens.toString())
            .setNumThreads(config.getNumThreads())
            .setDebug(false)
            .build();
        OfflineRecognizerConfig recognizerConfig = OfflineRecognizerConfig.builder()
            .setOfflineModelConfig(modelConfig)
            .setDecodingMethod("greedy_search")
            .build();

        synchronized (recognizerLock) {
            recognizer = new OfflineRecognizer(recognizerConfig);
        }
        logger.info(
            "{} loaded with sherpa-onnx: model={}, tokens={}",
            config.getModelType(),
            model,
            tokens
        );
    }

    private static Path requireRegularFile(String value, String description) throws Exception {
        if (value == null || value.isBlank()) {
            throw new Exception(description + " path is empty");
        }
        Path path = Path.of(value).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw new Exception(description + " not found: " + path);
        }
        return path;
    }

    /**
     * Starts capture and a separate bounded provisional preview worker.
     */
    public boolean startListening(Consumer<String> onPartial, Consumer<String> onFinal) {
        synchronized (lifecycleLock) {
            if (!isRecognizerReady()) {
                logger.warn("Cannot start recording because the local recognizer is unavailable");
                return false;
            }
            if (recognitionThread != null && recognitionThread.isAlive()) {
                logger.warn("Cannot start recording while the previous utterance is still processing");
                return false;
            }

            captureRequested = true;
            cancelled = false;
            lastError = "";
            recognitionThread = new Thread(
                () -> captureThenRecognize(onPartial, onFinal),
                "speech-recognition"
            );
            recognitionThread.setDaemon(true);
            recognitionThread.start();
            return true;
        }
    }

    /**
     * Signals the capture thread and returns immediately. Decoding and the
     * final callback remain on the worker thread so the Windows keyboard hook
     * is never blocked by model inference.
     */
    public void stopListening() {
        captureRequested = false;
    }

    /** Cancel rather than decode/inject an utterance when the service is disabled. */
    public void cancelListening() {
        cancelled = true;
        captureRequested = false;
        RollingPreview preview = activePreview;
        if (preview != null) preview.close();
        TargetDataLine line = activeLine;
        if (line != null) line.close();
    }

    public String getLastError() { return lastError; }

    public boolean isBusy() {
        synchronized (lifecycleLock) {
            return recognitionThread != null && recognitionThread.isAlive();
        }
    }

    private boolean isRecognizerReady() {
        synchronized (recognizerLock) {
            return recognizer != null;
        }
    }

    private void captureThenRecognize(Consumer<String> onPartial, Consumer<String> onFinal) {
        try (RollingPreview audio = new RollingPreview(sampleRate, this::recognize,
                text -> { if (!cancelled && onPartial != null) onPartial.accept(text); })) {
            activePreview = audio;
            if (cancelled) return;
            captureMicrophone(audio);
            if (!cancelled) audio.finish(text -> deliverFinal(onFinal, text));
        } catch (Exception e) {
            if (!cancelled) {
                lastError = "本地麦克风或识别失败，请检查音频设备和模型";
                logger.warn("Local speech capture or recognition failed: {}", e.getClass().getSimpleName());
                deliverFinal(onFinal, "");
            }
        } finally {
            captureRequested = false;
            activeLine = null;
            activePreview = null;
            synchronized (lifecycleLock) {
                if (Thread.currentThread() == recognitionThread) {
                    recognitionThread = null;
                }
            }
        }
    }

    private void deliverFinal(Consumer<String> callback, String text) {
        if (cancelled || callback == null) return;
        try { callback.accept(text); }
        catch (RuntimeException e) { logger.warn("Speech final callback failed: {}", e.getClass().getSimpleName()); }
    }

    private void captureMicrophone(RollingPreview audio) throws Exception {
        AudioFormat format = pcmFormat(sampleRate);
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
        if (!AudioSystem.isLineSupported(info)) {
            throw new Exception("Default microphone does not support 16 kHz mono PCM");
        }

        TargetDataLine line = (TargetDataLine) AudioSystem.getLine(info);
        activeLine = line;
        try {
            line.open(format);
            line.start();
            byte[] buffer = new byte[4096];
            while (captureRequested) {
                int bytesRead = line.read(buffer, 0, buffer.length);
                if (bytesRead > 0) {
                    if (!audio.append(buffer, bytesRead)) captureRequested = false;
                }
            }
        } finally {
            try {
                line.stop();
            } catch (Exception ignored) {
                // The line may already be closed during application shutdown.
            }
            line.close();
        }
    }

    public String recognize(byte[] pcm16LittleEndian) {
        if (pcm16LittleEndian == null || pcm16LittleEndian.length < BYTES_PER_SAMPLE) {
            return "";
        }

        return recognize(pcm16LittleEndianToFloat(pcm16LittleEndian), sampleRate);
    }

    static float[] pcm16LittleEndianToFloat(byte[] pcm16LittleEndian) {
        int sampleCount = pcm16LittleEndian == null
            ? 0
            : pcm16LittleEndian.length / BYTES_PER_SAMPLE;
        float[] samples = new float[sampleCount];
        for (int i = 0; i < sampleCount; i++) {
            int low = pcm16LittleEndian[i * 2] & 0xff;
            int high = pcm16LittleEndian[i * 2 + 1];
            short value = (short) ((high << 8) | low);
            samples[i] = value / 32768.0f;
        }
        return samples;
    }

    String recognize(float[] samples, int inputSampleRate) {
        if (samples == null || samples.length == 0) {
            return "";
        }

        synchronized (recognizerLock) {
            if (recognizer == null) {
                throw new IllegalStateException("Local recognizer is not initialized");
            }

            OfflineStream stream = recognizer.createStream();
            try {
                stream.acceptWaveform(samples, inputSampleRate);
                recognizer.decode(stream);
                OfflineRecognizerResult decoded = recognizer.getResult(stream);
                String text = decoded == null || decoded.getText() == null
                    ? ""
                    : decoded.getText().trim();
                if (decoded != null) {
                    logger.info(
                        "Local speech decoded: language={}, emotion={}, event={}, textLength={}",
                        decoded.getLang(),
                        decoded.getEmotion(),
                        decoded.getEvent(),
                        text.length()
                    );
                }
                return text;
            } finally {
                stream.release();
            }
        }
    }

    public String recognizeFromFile(String filePath) throws Exception {
        AudioFormat target = pcmFormat(sampleRate);
        try (
            AudioInputStream source = AudioSystem.getAudioInputStream(Path.of(filePath).toFile());
            AudioInputStream pcm = AudioSystem.getAudioInputStream(target, source)
        ) {
            return recognize(pcm.readAllBytes());
        }
    }

    private static AudioFormat pcmFormat(int rate) {
        return new AudioFormat(rate, BITS_PER_SAMPLE, 1, true, false);
    }

    public void release() {
        cancelListening();

        Thread worker;
        synchronized (lifecycleLock) {
            worker = recognitionThread;
        }
        if (worker != null && worker != Thread.currentThread() && worker.isAlive()) {
            TargetDataLine line = activeLine;
            if (line != null) {
                line.close();
            }
            try {
                worker.join(RELEASE_JOIN_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (worker.isAlive()) {
                logger.warn("Local speech worker did not stop; native recognizer release deferred");
                return;
            }
        }

        synchronized (recognizerLock) {
            if (recognizer != null) {
                recognizer.release();
                recognizer = null;
            }
        }
    }
}
