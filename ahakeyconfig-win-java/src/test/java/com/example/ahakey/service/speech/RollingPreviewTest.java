package com.example.ahakey.service.speech;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class RollingPreviewTest {
    @Test void emitsProvisionalAndFinalWithoutBlockingCapture() throws Exception {
        CountDownLatch decoding = new CountDownLatch(1), release = new CountDownLatch(1);
        List<String> partials = new CopyOnWriteArrayList<>(), finals = new CopyOnWriteArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        try (RollingPreview preview = new RollingPreview(100, bytes -> {
            if (calls.getAndIncrement() == 0) {
                decoding.countDown();
                try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            return "samples:" + bytes.length;
        }, partials::add)) {
            preview.append(new byte[200], 200);
            assertTrue(decoding.await(3, TimeUnit.SECONDS));
            // Appending is independent from the blocked inference worker.
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(1),
                () -> preview.append(new byte[200], 200));
            release.countDown();
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (partials.isEmpty() && System.nanoTime() < end) Thread.yield();
            assertFalse(partials.isEmpty());
            preview.finish(finals::add);
            assertEquals(List.of("samples:400"), finals);
            assertFalse(preview.append(new byte[20], 20));
        } finally { release.countDown(); }
    }

    @Test void cancelledInferenceCannotPublishLatePreviewOrFinal() throws Exception {
        CountDownLatch decoding = new CountDownLatch(1), release = new CountDownLatch(1);
        List<String> results = new CopyOnWriteArrayList<>();
        RollingPreview preview = new RollingPreview(100, bytes -> {
            decoding.countDown();
            boolean waiting = true;
            while (waiting) try { release.await(); waiting = false; } catch (InterruptedException ignored) {}
            return "late";
        }, results::add);
        preview.append(new byte[200], 200);
        assertTrue(decoding.await(3, TimeUnit.SECONDS));
        preview.close();
        release.countDown();
        preview.finish(results::add);
        assertTrue(results.isEmpty());
    }

    @Test void boundsUtteranceAndRollingSnapshot() throws Exception {
        List<Integer> sizes = new CopyOnWriteArrayList<>();
        try (RollingPreview preview = new RollingPreview(10, bytes -> {
            sizes.add(bytes.length); return "ok";
        }, ignored -> {})) {
            byte[] many = new byte[10 * 2 * 180];
            assertFalse(preview.append(many, many.length));
            assertEquals(10 * 2 * 120, preview.bufferedBytes());
            preview.finish(ignored -> {});
            assertEquals(2400, sizes.get(sizes.size() - 1));
            assertTrue(sizes.subList(0, sizes.size() - 1).stream().allMatch(size -> size <= 300));
        }
    }
}
