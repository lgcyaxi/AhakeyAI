package com.example.ahakey.service.speech;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Bounded provisional previews for an offline recognizer. The newest 15 seconds
 * replace any queued snapshot; this is not a native streaming ASR decoder.
 * Only finish() decodes the entire utterance and delivers a final result.
 */
public final class RollingPreview implements AutoCloseable {
    public static final int MAX_SECONDS = 120;
    private final byte[] audio;
    private final int windowBytes;
    private final int intervalBytes;
    private final Function<byte[], String> decode;
    private final Consumer<String> partial;
    private final AtomicReference<byte[]> pending = new AtomicReference<>();
    private final Object signal = new Object();
    private final Thread worker;
    private int size;
    private int lastScheduled;
    private boolean accepting = true;
    private volatile boolean cancelled;
    private volatile boolean finishing;

    public RollingPreview(int sampleRate, Function<byte[], String> decode, Consumer<String> partial) {
        this.audio = new byte[sampleRate * 2 * MAX_SECONDS];
        this.windowBytes = sampleRate * 2 * 15;
        this.intervalBytes = sampleRate * 2;
        this.decode = decode;
        this.partial = partial;
        worker = new Thread(this::run, "speech-provisional-preview");
        worker.setDaemon(true);
        worker.start();
    }

    /** Called only by capture; never waits for inference. False means the limit was reached. */
    public synchronized boolean append(byte[] bytes, int length) {
        if (!accepting || cancelled) return false;
        int count = Math.min(length, audio.length - size);
        System.arraycopy(bytes, 0, audio, size, count);
        size += count;
        if (size - lastScheduled >= intervalBytes) {
            lastScheduled = size;
            pending.set(Arrays.copyOfRange(audio, Math.max(0, size - windowBytes), size));
            synchronized (signal) { signal.notifyAll(); }
        }
        return size < audio.length;
    }

    private void run() {
        while (true) {
            synchronized (signal) {
                while (pending.get() == null && !isFinishing()) {
                    try { signal.wait(); }
                    catch (InterruptedException e) { return; }
                }
            }
            synchronized (this) { if (cancelled || finishing) return; }
            byte[] snapshot = pending.getAndSet(null);
            if (snapshot == null) continue;
            try {
                String text = decode.apply(snapshot);
                synchronized (this) {
                    if (!cancelled && !finishing && partial != null && text != null && !text.isBlank())
                        partial.accept(text);
                }
            } catch (RuntimeException ignored) {
                // A preview failure must not discard the final utterance.
            }
        }
    }

    private boolean isFinishing() { return finishing || cancelled; }

    /** Worker-only; waits for any in-flight preview before final decoding. */
    public void finish(Consumer<String> finalResult) throws InterruptedException {
        byte[] complete;
        synchronized (this) {
            if (cancelled || finishing) return;
            accepting = false;
            finishing = true;
            pending.set(null);
            complete = Arrays.copyOf(audio, size);
        }
        synchronized (signal) { signal.notifyAll(); }
        worker.join();
        synchronized (this) { if (cancelled) return; }
        String text = complete.length == 0 ? "" : decode.apply(complete);
        synchronized (this) {
            if (!cancelled && finalResult != null) finalResult.accept(text);
        }
    }

    public synchronized int bufferedBytes() { return size; }

    /** Cancels both preview and final delivery. Native inference may finish in the background. */
    @Override public void close() {
        synchronized (this) { cancelled = true; accepting = false; pending.set(null); }
        synchronized (signal) { signal.notifyAll(); }
        worker.interrupt();
    }
}
