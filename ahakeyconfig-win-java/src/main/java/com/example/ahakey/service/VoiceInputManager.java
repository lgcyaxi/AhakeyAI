package com.example.ahakey.service;

import com.example.ahakey.config.ModelConfig;
import com.example.ahakey.config.SpeechSettings;
import com.example.ahakey.service.speech.DoubaoSpeechService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Owns local speech recognition and injection into the currently focused text
 * field. Activation is explicit; each key press starts one utterance and its
 * matching stop action produces one final decode.
 */
public class VoiceInputManager {

    private static final Logger logger = LoggerFactory.getLogger(VoiceInputManager.class);

    private volatile SpeechService speechService;
    private KeyboardInjector keyboardInjector;
    private volatile boolean isEnabled;
    private volatile boolean isActivated;
    private volatile boolean isRecording;
    private volatile Consumer<String> resultCallback;
    private volatile Consumer<String> partialCallback;
    private volatile Consumer<String> statusCallback;
    private volatile String initializationError = "Local speech service is disabled";
    private final Object deliveryGate = new Object();
    private volatile long generation;
    private volatile boolean initializing;
    private volatile SpeechSettings.Provider provider = SpeechSettings.Provider.LOCAL;

    public interface Consumer<T> {
        void accept(T value);
    }

    public enum VoiceStatus {
        IDLE("idle", "语音未启动"),
        READY("ready", "AhaKey 语音已就绪"),
        LOADING("loading", "正在准备语音引擎"),
        RECORDING("recording", "按住说话中"),
        RECOGNIZING("recognizing", "松开，正在识别"),
        PROCESSING("processing", "正在上屏"),
        STOPPED("stopped", "语音已停止"),
        ERROR("error", "AhaKey 语音不可用");

        private final String code;
        private final String message;

        VoiceStatus(String code, String message) {
            this.code = code;
            this.message = message;
        }

        public String getCode() {
            return code;
        }

        public String getMessage() {
            return message;
        }
    }

    record ModelFiles(Path model, Path tokens) {
    }

    public void initialize() {
        provider = SpeechSettings.getInstance().getProvider();
        isEnabled = true;
        initializationError = provider == SpeechSettings.Provider.LOCAL
            ? "本地模型将在启用时加载；未安装时请在设置中下载" : "豆包将在按下录音时连接";
    }

    public SpeechSettings.Provider getProvider() { return provider; }

    /** Call on a settings worker after an explicit provider/credential change. */
    public void reload() {
        stopVoiceInput();
        SpeechService old = speechService;
        speechService = null;
        if (old != null) old.release();
        initialize();
    }

    private void prepareService(long expectedGeneration) {
        SpeechService candidateSpeechService = null;
        try {
            if (provider == SpeechSettings.Provider.DOUBAO) {
                SpeechSettings settings = SpeechSettings.getInstance();
                if (settings.getAppId().isBlank() || !settings.hasCredentials())
                    throw new Exception("请在语音设置中保存豆包 App ID 和 Access Token");
                candidateSpeechService = new DoubaoSpeechService(settings);
            } else {
                ModelConfig config = ModelConfig.getInstance();
                ModelFiles files;
                Path installed = ModelDownloadService.defaultModelDirectory();
                if (new ModelDownloadService().isInstalled()) {
                    files = new ModelFiles(installed.resolve("model.int8.onnx"), installed.resolve("tokens.txt"));
                } else {
                    files = findModelFiles(config.getModelPath(), config.getTokensPath(),
                        applicationDirectory(), Path.of("").toAbsolutePath().normalize());
                }
                candidateSpeechService = new SpeechService();
                candidateSpeechService.initialize(files.model().toString(), files.tokens().toString());
            }
            synchronized (deliveryGate) {
                if (generation != expectedGeneration) { candidateSpeechService.release(); return; }
                if (keyboardInjector == null) keyboardInjector = new KeyboardInjector();
                speechService = candidateSpeechService;
                initializationError = "";
                initializing = false;
                isActivated = true;
                notifyStatus(VoiceStatus.READY);
            }
        } catch (Exception | LinkageError e) {
            if (candidateSpeechService != null) candidateSpeechService.release();
            synchronized (deliveryGate) {
                if (generation != expectedGeneration) return;
                initializing = false; isActivated = false;
                initializationError = provider == SpeechSettings.Provider.LOCAL
                    ? "本地模型不可用，请在语音设置中下载或检查模型文件"
                    : "豆包不可用，请在语音设置中保存 App ID 和 Access Token";
                notifyStatus(VoiceStatus.ERROR);
            }
        }
    }

    static ModelFiles findModelFiles(
        String modelPath,
        String tokensPath,
        Path applicationDirectory,
        Path workingDirectory
    ) throws Exception {
        Path configuredModel = parsePath(modelPath, "SenseVoice model");
        Path configuredTokens = parsePath(tokensPath, "SenseVoice tokens");
        if (configuredModel.isAbsolute() || configuredTokens.isAbsolute()) {
            return new ModelFiles(
                resolveRequiredFile(
                    modelPath,
                    "SenseVoice model",
                    applicationDirectory,
                    workingDirectory
                ),
                resolveRequiredFile(
                    tokensPath,
                    "SenseVoice tokens",
                    applicationDirectory,
                    workingDirectory
                )
            );
        }

        Set<Path> roots = searchRoots(applicationDirectory, workingDirectory);
        StringBuilder attempted = new StringBuilder();
        for (Path root : roots) {
            Path model = root.resolve(configuredModel).normalize();
            Path tokens = root.resolve(configuredTokens).normalize();
            if (Files.isRegularFile(model) && Files.isRegularFile(tokens)) {
                return new ModelFiles(model, tokens);
            }
            attempted.append(System.lineSeparator())
                .append("  - ")
                .append(model)
                .append(" + ")
                .append(tokens);
        }
        throw new Exception("SenseVoice model pair not found. Attempted:" + attempted);
    }

    static Path resolveRequiredFile(
        String configuredPath,
        String description,
        Path applicationDirectory,
        Path workingDirectory
    ) throws Exception {
        if (configuredPath == null || configuredPath.isBlank()) {
            throw new Exception(description + " path is empty");
        }

        Path configured = parsePath(configuredPath, description);

        Set<Path> candidates = new LinkedHashSet<>();
        if (configured.isAbsolute()) {
            candidates.add(configured.normalize());
        } else {
            for (Path root : searchRoots(applicationDirectory, workingDirectory)) {
                candidates.add(root.resolve(configured).normalize());
            }
        }

        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }

        String attempted = candidates.stream()
            .map(Path::toString)
            .reduce((left, right) -> left + System.lineSeparator() + "  - " + right)
            .map(value -> "  - " + value)
            .orElse("  - no valid search roots");
        throw new Exception(
            description + " not found. Attempted:" + System.lineSeparator() + attempted
        );
    }

    private static Path parsePath(String value, String description) throws Exception {
        if (value == null || value.isBlank()) {
            throw new Exception(description + " path is empty");
        }
        try {
            return Path.of(value);
        } catch (InvalidPathException e) {
            throw new Exception(description + " path is invalid: " + value, e);
        }
    }

    private static Set<Path> searchRoots(Path applicationDirectory, Path workingDirectory) {
        Set<Path> roots = new LinkedHashSet<>();
        if (applicationDirectory != null) {
            Path app = applicationDirectory.toAbsolutePath().normalize();
            roots.add(app);
            if (app.getParent() != null) {
                roots.add(app.getParent());
            }
        }
        if (workingDirectory != null) {
            roots.add(workingDirectory.toAbsolutePath().normalize());
        }
        return roots;
    }

    private Path applicationDirectory() {
        try {
            var codeSource = getClass().getProtectionDomain().getCodeSource();
            if (codeSource == null || codeSource.getLocation() == null) {
                return null;
            }
            URI locationUri = codeSource.getLocation().toURI();
            Path location = Path.of(locationUri).toAbsolutePath().normalize();
            return Files.isDirectory(location) ? location : location.getParent();
        } catch (Exception e) {
            logger.debug("Cannot resolve application directory: {}", e.getMessage());
            return null;
        }
    }

    public void startVoiceInput() {
        startVoiceInput(null, null);
    }

    public void startVoiceInput(Consumer<String> callback) {
        startVoiceInput(callback, null);
    }

    public void setStatusCallback(Consumer<String> statusCallback) {
        this.statusCallback = statusCallback;
    }

    private void notifyStatus(VoiceStatus status) {
        Consumer<String> callback = statusCallback;
        if (callback != null) {
            try {
                callback.accept(status.getCode() + ":" + status.getMessage());
            } catch (Exception e) {
                logger.error("Voice status callback failed: {}", e.getMessage(), e);
            }
        }
    }

    public synchronized void startVoiceInput(
        Consumer<String> resultCallback,
        Consumer<String> partialCallback
    ) {
        if (!isEnabled) {
            logger.warn("Local speech is unavailable: {}", initializationError);
            notifyStatus(VoiceStatus.ERROR);
            return;
        }
        if (isActivated || initializing) {
            logger.debug("Local speech service is already active");
            return;
        }

        this.resultCallback = resultCallback;
        this.partialCallback = partialCallback;
        if (speechService == null) {
            initializing = true;
            long expectedGeneration = ++generation;
            notifyStatus(VoiceStatus.LOADING);
            Thread prepare = new Thread(() -> prepareService(expectedGeneration), "speech-engine-load");
            prepare.setDaemon(true);
            prepare.start();
            return;
        }
        isActivated = true;
        notifyStatus(VoiceStatus.READY);
        logger.info("Local speech service activated");
    }

    public synchronized void stopVoiceInput() {
        synchronized (deliveryGate) {
            generation++;
            initializing = false;
            isRecording = false;
            isActivated = false;
            resultCallback = null;
            partialCallback = null;
        }
        SpeechService service = speechService;
        if (service != null) service.cancelListening();
        notifyStatus(VoiceStatus.STOPPED);
        logger.info("Local speech service deactivated");
    }

    public synchronized void startRecording() {
        if (!isActivated || speechService == null) {
            logger.warn("Local speech service is not active");
            notifyStatus(VoiceStatus.ERROR);
            return;
        }
        if (isRecording) {
            return;
        }
        if (speechService.isBusy()) {
            logger.warn("Previous local utterance is still processing");
            notifyStatus(VoiceStatus.RECOGNIZING);
            return;
        }

        isRecording = true;
        long expectedGeneration = generation;
        boolean started = speechService.startListening(
            text -> {
                synchronized (deliveryGate) {
                    if (generation == expectedGeneration && isActivated) onPartialResult(text);
                }
            },
            text -> {
                synchronized (deliveryGate) {
                    if (generation == expectedGeneration && isActivated) onFinalResult(text);
                }
            }
        );
        if (!started) {
            isRecording = false;
            notifyStatus(VoiceStatus.ERROR);
            return;
        }

        notifyStatus(VoiceStatus.RECORDING);
        logger.info("Local microphone recording started");
    }

    public synchronized void stopRecording() {
        if (!isRecording) {
            return;
        }

        isRecording = false;
        notifyStatus(VoiceStatus.RECOGNIZING);
        speechService.stopListening();
        logger.info("Local microphone recording stopped; decoding asynchronously");
    }

    private void onPartialResult(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        Consumer<String> callback = partialCallback;
        if (callback != null) {
            try {
                callback.accept(text);
            } catch (Exception e) {
                logger.error("Partial result callback failed: {}", e.getMessage(), e);
            }
        }
    }

    private void onFinalResult(String text) {
        isRecording = false;
        if (!isActivated) return;
        String error = speechService == null ? "" : speechService.getLastError();
        if (!error.isBlank()) {
            initializationError = error;
            isActivated = false;
            notifyStatus(VoiceStatus.ERROR);
            return;
        }
        notifyStatus(VoiceStatus.PROCESSING);
        String finalResult = text == null ? "" : text.trim();
        logger.info("Local speech recognition completed with {} characters", finalResult.length());

        String processedText = finalResult.isEmpty() ? "" : processWithAhaType(finalResult);
        Consumer<String> callback = resultCallback;
        if (callback != null) {
            try {
                callback.accept(processedText);
            } catch (Exception e) {
                logger.error("Final result callback failed: {}", e.getMessage(), e);
            }
        }
        if (!processedText.isEmpty()) {
            injectText(processedText);
        }

        notifyStatus(isActivated ? VoiceStatus.READY : VoiceStatus.STOPPED);
    }

    private String processWithAhaType(String text) {
        // AhaType post-processing remains optional future work.
        return text;
    }

    private void injectText(String text) {
        if (keyboardInjector == null || text == null || text.isEmpty()) {
            return;
        }
        try {
            keyboardInjector.injectText(text);
        } catch (Exception e) {
            logger.error("Text injection failed: {}", e.getMessage(), e);
        }
    }

    public void toggleEnabled() {
        if (!isEnabled) {
            logger.warn("Cannot enable local speech because initialization failed: {}", initializationError);
            return;
        }
        if (isActivated) {
            stopVoiceInput();
        } else {
            startVoiceInput();
        }
    }

    public boolean isEnabled() {
        return isEnabled;
    }

    public boolean isActivated() {
        return isActivated;
    }

    public boolean isRecording() {
        return isRecording;
    }

    public String getAvailabilityMessage() {
        if (!initializationError.isBlank()) return initializationError;
        return provider == SpeechSettings.Provider.LOCAL ? "SenseVoice 本地模型已加载，字幕为暂定预览"
            : "豆包流式识别已配置，录音将发送至火山引擎";
    }

    public void shutdown() {
        stopVoiceInput();
        if (speechService != null) {
            speechService.release();
        }
        if (keyboardInjector != null) {
            keyboardInjector.release();
        }
        isEnabled = false;
        isActivated = false;
        isRecording = false;
        logger.info("VoiceInputManager shut down");
    }

    public SpeechService getSpeechService() {
        return speechService;
    }

    public KeyboardInjector getKeyboardInjector() {
        return keyboardInjector;
    }
}
