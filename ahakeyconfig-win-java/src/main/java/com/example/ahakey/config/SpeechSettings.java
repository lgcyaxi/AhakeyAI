package com.example.ahakey.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Crypt32Util;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;

/** Provider preference and current-Windows-user DPAPI encrypted credentials. */
public final class SpeechSettings {
    public enum Provider { LOCAL, DOUBAO }
    public static final String DEFAULT_RESOURCE = "volc.bigasr.sauc.duration";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> RESOURCES = Set.of(DEFAULT_RESOURCE,
        "volc.bigasr.sauc.concurrent", "volc.seedasr.sauc.duration", "volc.seedasr.sauc.concurrent");
    private static final SpeechSettings INSTANCE = new SpeechSettings(dataDirectory().resolve("speech-settings.json"));
    private final Path file;
    private Provider provider = Provider.LOCAL;
    private String appId = "";
    private String resourceId = DEFAULT_RESOURCE;
    private String protectedAccessKey = "";

    public static SpeechSettings getInstance() { return INSTANCE; }
    public static Path dataDirectory() {
        String local = System.getenv("LOCALAPPDATA");
        return local != null && !local.isBlank() ? Path.of(local, "AhaKey Studio")
            : Path.of(System.getProperty("user.home"), ".ahakey-studio");
    }
    public SpeechSettings(Path file) {
        this.file = file;
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > 64 * 1024) return;
            JsonNode node = JSON.readTree(file.toFile());
            provider = Provider.valueOf(node.path("provider").asText("LOCAL"));
            setAppId(node.path("appId").asText(""));
            setResourceId(node.path("resourceId").asText(DEFAULT_RESOURCE));
            protectedAccessKey = node.path("accessKeyDpapi").asText("");
        } catch (Exception ignored) {
            provider = Provider.LOCAL; appId = ""; resourceId = DEFAULT_RESOURCE; protectedAccessKey = "";
        }
    }
    public synchronized Provider getProvider() { return provider; }
    public synchronized void setProvider(Provider provider) { this.provider = java.util.Objects.requireNonNull(provider); }
    public synchronized String getAppId() { return appId; }
    public synchronized void setAppId(String value) {
        String clean = value == null ? "" : value.trim();
        if (!clean.matches("[A-Za-z0-9_-]{0,128}")) throw new IllegalArgumentException("App ID 格式无效");
        appId = clean;
    }
    public synchronized String getResourceId() { return resourceId; }
    public synchronized void setResourceId(String value) {
        if (!RESOURCES.contains(value)) throw new IllegalArgumentException("请选择有效的豆包语音资源 ID");
        resourceId = value;
    }
    public synchronized boolean hasCredentials() { return !protectedAccessKey.isBlank(); }
    public synchronized void saveCredentials(String accessKey) throws IOException {
        if (!Platform.isWindows()) throw new IOException("此客户端仅支持 Windows DPAPI 凭据存储");
        if (accessKey == null || accessKey.isBlank() || accessKey.length() > 4096 ||
            accessKey.chars().anyMatch(c -> c < 33 || c > 126))
            throw new IOException("Access Token 格式无效");
        byte[] plain = accessKey.getBytes(StandardCharsets.UTF_8);
        try {
            protectedAccessKey = Base64.getEncoder().encodeToString(Crypt32Util.cryptProtectData(plain));
            save();
        } catch (RuntimeException e) { throw new IOException("Windows 凭据加密失败"); }
        finally { Arrays.fill(plain, (byte) 0); }
    }
    /** Backend-only. Never display, serialize, or log the returned secret. */
    public synchronized String loadAccessKey() throws IOException {
        if (!Platform.isWindows() || !hasCredentials()) throw new IOException("请先保存豆包 Access Token");
        byte[] plain = null;
        try {
            plain = Crypt32Util.cryptUnprotectData(Base64.getDecoder().decode(protectedAccessKey));
            return new String(plain, StandardCharsets.UTF_8);
        } catch (RuntimeException e) { throw new IOException("无法解密凭据，请在当前 Windows 账户重新保存"); }
        finally { if (plain != null) Arrays.fill(plain, (byte) 0); }
    }
    public synchronized void clearCredentials() throws IOException { protectedAccessKey = ""; save(); }
    public synchronized void save() throws IOException {
        ObjectNode node = JSON.createObjectNode();
        node.put("provider", provider.name()).put("appId", appId).put("resourceId", resourceId)
            .put("accessKeyDpapi", protectedAccessKey);
        Path parent = file.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, "speech-settings-", ".tmp");
        try {
            JSON.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), node);
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
}
