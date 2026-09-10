package com.example.ahakey.service;

import com.example.ahakey.config.SpeechSettings;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.HexFormat;
import java.util.function.Consumer;
import java.util.concurrent.*;

/** Explicit opt-in, pinned direct-file download. No archives or background startup downloads. */
public final class ModelDownloadService {
    public static final String REVISION = "2365baeacb507f821a0c8120fcee3d484dba7a07";
    public static final String BASE_URL = "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/" + REVISION + "/";
    public static final String MODEL_SHA256 = "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51";
    public static final String TOKENS_SHA256 = "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc";
    private static final long MODEL_BYTES = 239233841;
    private static final long TOKENS_BYTES = 315894;
    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "model-download-timeout");
        thread.setDaemon(true); return thread;
    });
    private final Path directory;
    public ModelDownloadService() { this(defaultModelDirectory()); }
    public ModelDownloadService(Path directory) { this.directory = directory.toAbsolutePath().normalize(); }
    public static Path defaultModelDirectory() {
        return SpeechSettings.dataDirectory().resolve("models").resolve("sensevoice-int8-2024-07-17");
    }
    /** Cheap readiness check; every downloaded/imported file is hashed before publication. */
    public boolean isInstalled() {
        try { return Files.size(directory.resolve("model.int8.onnx")) == MODEL_BYTES &&
            Files.size(directory.resolve("tokens.txt")) == TOKENS_BYTES; }
        catch (IOException e) { return false; }
    }
    /** Run on a worker thread. Interruption cancels and removes only the newly-created temporary file. */
    public synchronized Path download(Consumer<Double> progress) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
        transfer(client, null, "model.int8.onnx", MODEL_SHA256, MODEL_BYTES, 0, progress);
        transfer(client, null, "tokens.txt", TOKENS_SHA256, TOKENS_BYTES, MODEL_BYTES, progress);
        return directory;
    }
    /** Explicitly reuse an existing official bundle; hashes are checked before either file is replaced. */
    public synchronized Path importFrom(Path source, Consumer<Double> progress) throws IOException, InterruptedException {
        validate(source.resolve("model.int8.onnx"), MODEL_SHA256, MODEL_BYTES);
        validate(source.resolve("tokens.txt"), TOKENS_SHA256, TOKENS_BYTES);
        transfer(null, source, "model.int8.onnx", MODEL_SHA256, MODEL_BYTES, 0, progress);
        transfer(null, source, "tokens.txt", TOKENS_SHA256, TOKENS_BYTES, MODEL_BYTES, progress);
        return directory;
    }
    private void transfer(HttpClient client, Path source, String name, String hash, long expectedSize,
                          long completed, Consumer<Double> progress) throws IOException, InterruptedException {
        Files.createDirectories(directory);
        Path target = directory.resolve(name);
        if (Files.isRegularFile(target)) {
            try { validate(target, hash, expectedSize); report(progress, completed + expectedSize); return; }
            catch (IOException ignored) { /* Replace only after a verified temporary download succeeds. */ }
        }
        Path temporary = Files.createTempFile(directory, name + "-", ".part");
        try {
            InputStream input;
            if (source != null) input = Files.newInputStream(source.resolve(name));
            else {
                HttpRequest request = HttpRequest.newBuilder(URI.create(BASE_URL + name))
                    .timeout(Duration.ofMinutes(15)).GET().build();
                HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() != 200) {
                    response.body().close();
                    throw new IOException("模型下载失败，HTTP " + response.statusCode());
                }
                input = response.body();
            }
            Thread caller = Thread.currentThread();
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(15);
            ScheduledFuture<?> timeout = WATCHDOG.scheduleWithFixedDelay(() -> {
                if (caller.isInterrupted() || System.nanoTime() >= deadline) {
                    try { input.close(); } catch (IOException ignored) {}
                }
            }, 500, 500, TimeUnit.MILLISECONDS);
            try (input; OutputStream out = Files.newOutputStream(temporary)) {
                byte[] buffer = new byte[64 * 1024]; long count = 0; int length;
                while ((length = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException("模型下载已取消");
                    count += length;
                    if (count > expectedSize) throw new IOException("模型下载超过预期大小");
                    out.write(buffer, 0, length); report(progress, completed + count);
                }
            } finally { timeout.cancel(false); }
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("模型下载已取消");
            validate(temporary, hash, expectedSize);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
    private static void report(Consumer<Double> callback, long count) {
        if (callback != null) callback.accept((double) count / (MODEL_BYTES + TOKENS_BYTES));
    }
    private static void validate(Path path, String expected, long size) throws IOException {
        if (Files.size(path) != size) throw new IOException("模型文件大小校验失败");
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024]; int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            if (!HexFormat.of().formatHex(digest.digest()).equals(expected)) throw new IOException("模型 SHA-256 校验失败");
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
