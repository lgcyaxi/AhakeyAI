package com.example.ahakey.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;

/**
 * 模型配置管理器
 * 支持从配置文件动态加载模型路径和参数
 * 用户只需替换模型文件或修改配置文件即可切换模型，无需修改代码
 */
public class ModelConfig {
    
    private static final Logger logger = LoggerFactory.getLogger(ModelConfig.class);
    
    private static ModelConfig instance;
    private Properties properties;
    
    // 配置键名常量
    private static final String MODEL_ENABLED = "model.enabled";
    private static final String MODEL_PATH = "model.path";
    private static final String TOKENS_PATH = "tokens.path";
    private static final String MODEL_TYPE = "model.type";
    private static final String NUM_THREADS = "num_threads";
    private static final String SAMPLE_RATE = "sample_rate";
    private static final String N_FFT = "n_fft";
    private static final String HOP_LENGTH = "hop_length";
    private static final String N_MELS = "n_mels";
    private static final String FEATURE_DIM = "feature_dim";
    private static final String FRAMES_PER_CHUNK = "frames_per_chunk";
    private static final String LANGUAGE = "language";
    private static final String TEXT_NORM = "text_norm";
    private static final String STATUS_POLL_PERIOD = "status.poll.period.seconds";
    private static final String EXTERNAL_CONFIG_PROPERTY = "ahakey.model.config";
    
    // 默认配置值
    private static final boolean DEFAULT_MODEL_ENABLED = false;
    private static final String DEFAULT_MODEL_PATH = "models/model.int8.onnx";
    private static final String DEFAULT_TOKENS_PATH = "models/tokens.txt";
    private static final String DEFAULT_MODEL_TYPE = "SenseVoice INT8 2024-07-17";
    private static final int DEFAULT_NUM_THREADS = 4;
    private static final int DEFAULT_SAMPLE_RATE = 16000;
    private static final int DEFAULT_N_FFT = 512;
    private static final int DEFAULT_HOP_LENGTH = 160;
    private static final int DEFAULT_N_MELS = 80;
    private static final int DEFAULT_FEATURE_DIM = 560;
    private static final int DEFAULT_FRAMES_PER_CHUNK = 7;
    private static final int DEFAULT_LANGUAGE = 0;
    private static final int DEFAULT_TEXT_NORM = 0;
    private static final int DEFAULT_STATUS_POLL_PERIOD = 3;
    private Path externalConfigPath;
    
    private ModelConfig() {
        loadConfig();
    }
    
    /**
     * 获取单例实例
     */
    public static synchronized ModelConfig getInstance() {
        if (instance == null) {
            instance = new ModelConfig();
        }
        return instance;
    }
    
    /**
     * 重新加载配置文件
     */
    public void reload() {
        loadConfig();
    }
    
    /**
     * 加载配置文件
     */
    private void loadConfig() {
        properties = new Properties();
        
        // Load safe built-in defaults first.
        try (InputStream is = getClass().getResourceAsStream("/model_config.properties")) {
            if (is != null) {
                properties.load(is);
                logger.info("Bundled model configuration loaded");
            } else {
                logger.warn("Bundled model configuration not found; using defaults");
            }
        } catch (IOException e) {
            logger.error("Failed to load bundled model configuration: {}", e.getMessage(), e);
        }

        // A model-enabled app-image writes this file beside its application
        // JAR. It overrides the safe disabled defaults without modifying the
        // source tree or embedding machine-specific paths.
        externalConfigPath = findExternalConfig();
        if (externalConfigPath != null) {
            try (InputStream is = Files.newInputStream(externalConfigPath)) {
                properties.load(is);
                logger.info("External model configuration loaded: {}", externalConfigPath);
            } catch (IOException e) {
                logger.error(
                    "Failed to load external model configuration {}: {}",
                    externalConfigPath,
                    e.getMessage(),
                    e
                );
            }
        }
    }

    private Path findExternalConfig() {
        Set<Path> candidates = new LinkedHashSet<>();
        String explicit = System.getProperty(EXTERNAL_CONFIG_PROPERTY);
        if (explicit != null && !explicit.isBlank()) {
            candidates.add(Path.of(explicit).toAbsolutePath().normalize());
        }

        Path applicationDirectory = applicationDirectory();
        if (applicationDirectory != null) {
            candidates.add(applicationDirectory.resolve("model_config.properties").normalize());
        }
        candidates.add(
            Path.of("").toAbsolutePath().normalize().resolve("model_config.properties")
        );

        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
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
    
    /**
     * 获取模型启用状态
     * @return true 表示启用本地模型和语音输入功能，false 表示禁用
     */
    public boolean isEnabled() {
        return getBooleanProperty(MODEL_ENABLED, DEFAULT_MODEL_ENABLED);
    }
    
    /**
     * 获取模型文件路径
     */
    public String getModelPath() {
        return properties.getProperty(MODEL_PATH, DEFAULT_MODEL_PATH);
    }
    
    /**
     * 获取词汇表文件路径
     */
    public String getTokensPath() {
        return properties.getProperty(TOKENS_PATH, DEFAULT_TOKENS_PATH);
    }
    
    /**
     * 获取模型类型
     */
    public String getModelType() {
        return properties.getProperty(MODEL_TYPE, DEFAULT_MODEL_TYPE);
    }
    
    /**
     * 获取线程数
     */
    public int getNumThreads() {
        return getIntProperty(NUM_THREADS, DEFAULT_NUM_THREADS);
    }
    
    /**
     * 获取采样率
     */
    public int getSampleRate() {
        return getIntProperty(SAMPLE_RATE, DEFAULT_SAMPLE_RATE);
    }
    
    /**
     * 获取FFT点数
     */
    public int getNFFT() {
        return getIntProperty(N_FFT, DEFAULT_N_FFT);
    }
    
    /**
     * 获取帧移长度
     */
    public int getHopLength() {
        return getIntProperty(HOP_LENGTH, DEFAULT_HOP_LENGTH);
    }
    
    /**
     * 获取Mel频段数
     */
    public int getNMels() {
        return getIntProperty(N_MELS, DEFAULT_N_MELS);
    }
    
    /**
     * 获取输入特征维度
     */
    public int getFeatureDim() {
        return getIntProperty(FEATURE_DIM, DEFAULT_FEATURE_DIM);
    }
    
    /**
     * 获取每块帧数
     */
    public int getFramesPerChunk() {
        return getIntProperty(FRAMES_PER_CHUNK, DEFAULT_FRAMES_PER_CHUNK);
    }
    
    /**
     * 获取语言设置
     */
    public int getLanguage() {
        return getIntProperty(LANGUAGE, DEFAULT_LANGUAGE);
    }

    public String getLanguageCode() {
        String value = properties.getProperty(LANGUAGE, "auto").trim();
        return switch (value.toLowerCase()) {
            case "0" -> "auto";
            case "1" -> "zh";
            case "2" -> "en";
            case "3" -> "ja";
            case "4" -> "ko";
            case "5" -> "yue";
            default -> value;
        };
    }
    
    /**
     * 获取文本规范化设置
     */
    public int getTextNorm() {
        return getIntProperty(TEXT_NORM, DEFAULT_TEXT_NORM);
    }

    public boolean useInverseTextNormalization() {
        String value = properties.getProperty(TEXT_NORM);
        if (value == null || value.isBlank()) {
            return DEFAULT_TEXT_NORM != 0;
        }
        return "1".equals(value.trim()) || Boolean.parseBoolean(value.trim());
    }
    
    /**
     * 获取设备状态轮询周期（秒）
     */
    public int getStatusPollPeriodSeconds() {
        return getIntProperty(STATUS_POLL_PERIOD, DEFAULT_STATUS_POLL_PERIOD);
    }
    
    /**
     * 安全获取布尔属性
     */
    private boolean getBooleanProperty(String key, boolean defaultValue) {
        String value = properties.getProperty(key);
        if (value != null && !value.isEmpty()) {
            return Boolean.parseBoolean(value.trim());
        }
        return defaultValue;
    }
    
    /**
     * 安全获取整数属性
     */
    private int getIntProperty(String key, int defaultValue) {
        try {
            String value = properties.getProperty(key);
            if (value != null && !value.isEmpty()) {
                return Integer.parseInt(value.trim());
            }
        } catch (NumberFormatException e) {
            logger.warn("配置项 {} 值无效，使用默认值: {}", key, defaultValue);
        }
        return defaultValue;
    }
    
    /**
     * 打印当前配置信息
     */
    public void printConfig() {
        logger.debug("========== 模型配置 ==========");
        logger.debug("模型启用: {}", isEnabled());
        logger.debug("模型路径: {}", getModelPath());
        logger.debug("词汇表路径: {}", getTokensPath());
        logger.debug("模型类型: {}", getModelType());
        logger.debug("线程数: {}", getNumThreads());
        logger.debug("采样率: {}", getSampleRate());
        logger.debug("FFT点数: {}", getNFFT());
        logger.debug("帧移: {}", getHopLength());
        logger.debug("Mel频段: {}", getNMels());
        logger.debug("特征维度: {}", getFeatureDim());
        logger.debug("每块帧数: {}", getFramesPerChunk());
        logger.debug("语言: {}", getLanguageCode());
        logger.debug("文本规范化: {}", useInverseTextNormalization());
        logger.debug("外部配置: {}", externalConfigPath);
        logger.debug("==============================");
    }
}
