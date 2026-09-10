package com.example.ahakey.service.speech;

import com.example.ahakey.config.SpeechSettings;
import com.example.ahakey.service.SpeechService;
import javax.sound.sampled.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.*;

/** Explicitly selected cloud session. No connection or microphone is opened at construction. */
public final class DoubaoSpeechService extends SpeechService {
    public static final URI ENDPOINT = URI.create("wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async");
    private final SpeechSettings settings;
    private volatile Session current;
    private volatile String lastError = "";
    public DoubaoSpeechService(SpeechSettings settings) { this.settings = settings; }
    @Override public synchronized boolean startListening(Consumer<String> partial, Consumer<String> complete) {
        if (isBusy()) return false;
        if (settings.getProvider() != SpeechSettings.Provider.DOUBAO ||
            settings.getAppId().isBlank() || !settings.hasCredentials()) {
            lastError = "请明确选择豆包并保存 App ID 与 Access Token";
            return false;
        }
        lastError = "";
        Session session = new Session(partial, complete);
        current = session;
        session.worker = new Thread(session::run, "doubao-speech");
        session.worker.setDaemon(true); session.worker.start();
        return true;
    }
    @Override public void stopListening() { Session s = current; if (s != null) s.recording = false; }
    @Override public boolean isBusy() { Session s = current; return s != null && !s.done; }
    @Override public String getLastError() { return lastError; }
    @Override public void cancelListening() { Session s = current; if (s != null) s.cancel(); }
    @Override public void release() { cancelListening(); }

    private final class Session implements WebSocket.Listener {
        private final Consumer<String> partial, complete;
        private final ArrayBlockingQueue<byte[]> packets = new ArrayBlockingQueue<>(80);
        private final CompletableFuture<String> finalText = new CompletableFuture<>();
        private final ByteArrayOutputStream incoming = new ByteArrayOutputStream();
        private volatile boolean recording = true, cancelled, done, captureDone;
        private volatile TargetDataLine microphone;
        private volatile WebSocket socket;
        private Thread worker, capture;
        Session(Consumer<String> partial, Consumer<String> complete) {
            this.partial = partial; this.complete = complete;
        }
        void run() {
            String result = "";
            try {
                // Capture immediately on press so connection setup does not discard the first words.
                // The queue is bounded to 16 seconds; a stalled connection fails instead of dropping audio.
                capture = new Thread(this::capture, "doubao-microphone");
                capture.setDaemon(true); capture.start();
                HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
                socket = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(15))
                    .header("X-Api-App-Key", settings.getAppId())
                    .header("X-Api-Access-Key", settings.loadAccessKey())
                    .header("X-Api-Resource-Id", settings.getResourceId())
                    .header("X-Api-Connect-Id", UUID.randomUUID().toString())
                    .buildAsync(ENDPOINT, this).get(20, TimeUnit.SECONDS);
                if (cancelled) return;
                send(DoubaoProtocol.configuration());
                while (!cancelled && (!captureDone || !packets.isEmpty())) {
                    if (finalText.isCompletedExceptionally()) finalText.get();
                    byte[] packet = packets.poll(200, TimeUnit.MILLISECONDS);
                    if (packet != null) send(DoubaoProtocol.audio(packet, false));
                }
                if (cancelled) return;
                if (finalText.isCompletedExceptionally()) finalText.get();
                send(DoubaoProtocol.audio(new byte[0], true));
                result = finalText.get(20, TimeUnit.SECONDS);
            } catch (Exception e) {
                if (!cancelled) {
                    // Do not log request/response exceptions: headers can contain account secrets.
                    lastError = "豆包连接或识别失败，请检查网络、凭据和语音资源权限";
                    Throwable cause = e instanceof ExecutionException ? e.getCause() : e;
                    if (cause instanceof ProtocolFailure) lastError = cause.getMessage();
                }
            } finally {
                recording = false;
                TargetDataLine line = microphone;
                if (line != null) line.close();
                WebSocket ws = socket; if (ws != null) ws.abort();
                synchronized (this) {
                    try { if (!cancelled && complete != null) complete.accept(result); }
                    finally { done = true; }
                }
                packets.clear();
            }
        }
        private void send(byte[] bytes) throws Exception {
            if (cancelled) throw new CancellationException();
            socket.sendBinary(ByteBuffer.wrap(bytes), true).get(10, TimeUnit.SECONDS);
        }
        private void capture() {
            try {
                if (!recording || cancelled) return;
                AudioFormat format = new AudioFormat(16000, 16, 1, true, false);
                microphone = (TargetDataLine) AudioSystem.getLine(new DataLine.Info(TargetDataLine.class, format));
                microphone.open(format); microphone.start();
                byte[] block = new byte[6400]; // 200 ms, as recommended by the official bidirectional API.
                int captured = 0;
                while (recording && !cancelled && captured < 16000 * 2 * RollingPreview.MAX_SECONDS) {
                    int count = microphone.read(block, 0, block.length);
                    if (count > 0) {
                        captured += count;
                        if (!packets.offer(Arrays.copyOf(block, count)))
                            throw new IOException("Cloud upload cannot keep up with microphone");
                    }
                }
            } catch (Exception e) {
                if (!cancelled) finalText.completeExceptionally(new IOException("Microphone or upload failed"));
            } finally {
                recording = false; captureDone = true;
                TargetDataLine line = microphone; if (line != null) line.close();
            }
        }
        void cancel() {
            synchronized (this) { cancelled = true; recording = false; }
            finalText.cancel(false);
            WebSocket ws = socket; if (ws != null) ws.abort();
            TargetDataLine line = microphone; if (line != null) line.close();
            if (worker != null) worker.interrupt();
        }
        @Override public void onOpen(WebSocket ws) { ws.request(1); }
        @Override public CompletionStage<?> onBinary(WebSocket ws, ByteBuffer bytes, boolean last) {
            if (cancelled || done) { ws.request(1); return null; }
            try {
                if (incoming.size() + bytes.remaining() > DoubaoProtocol.MAX_FRAME_BYTES)
                    throw new IOException("豆包响应过大");
                byte[] chunk = new byte[bytes.remaining()]; bytes.get(chunk); incoming.write(chunk);
                if (last) {
                    DoubaoProtocol.Result result = DoubaoProtocol.parse(incoming.toByteArray());
                    incoming.reset();
                    synchronized (this) {
                        if (!cancelled && !done) {
                            if (result.finalResult()) finalText.complete(result.text());
                            else if (!finalText.isDone() && !result.text().isBlank() && partial != null) partial.accept(result.text());
                        }
                    }
                }
            } catch (IOException e) {
                finalText.completeExceptionally(new ProtocolFailure(e.getMessage()));
            } catch (RuntimeException e) {
                finalText.completeExceptionally(new IOException("Cloud result callback failed"));
            } finally { ws.request(1); }
            return null;
        }
        @Override public CompletionStage<?> onClose(WebSocket ws, int status, String reason) {
            if (!finalText.isDone()) finalText.completeExceptionally(new IOException("Connection closed before final result"));
            return null;
        }
        @Override public void onError(WebSocket ws, Throwable error) {
            finalText.completeExceptionally(new IOException("Cloud connection failed"));
        }
    }
    private static final class ProtocolFailure extends IOException {
        ProtocolFailure(String message) { super(message); }
    }
}
