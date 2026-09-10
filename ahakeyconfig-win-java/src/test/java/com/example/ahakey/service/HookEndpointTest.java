package com.example.ahakey.service;

import com.example.ahakey.model.IDEState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HookEndpointTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void publishesReadsAndClearsOnlyTheOwningEndpoint() throws Exception {
        Path path = temporaryDirectory.resolve("active-endpoint.json");
        HookEndpoint.Descriptor first = HookEndpoint.publish(path, 18765);

        assertEquals(first, HookEndpoint.read(path));
        assertEquals(first, HookEndpoint.readLive(path));

        HookEndpoint.Descriptor replacement = HookEndpoint.publish(path, 18766);
        HookEndpoint.clearIfOwned(path, first);
        assertEquals(replacement, HookEndpoint.read(path));

        HookEndpoint.clearIfOwned(path, replacement);
        assertFalse(Files.exists(path));
    }

    @Test
    void serverPublishesThePortItActuallyBound() throws Exception {
        Path path = temporaryDirectory.resolve("active-endpoint.json");
        try (ServerSocket occupied = occupyPortWithAvailableSuccessor()) {
            HookDispatchServer server = new HookDispatchServer(null, occupied.getLocalPort(), path);
            try {
                server.start();

                assertTrue(server.isRunning());
                assertNotEquals(occupied.getLocalPort(), server.getActualPort());
                assertEquals(server.getActualPort(), HookEndpoint.read(path).port());
            } finally {
                server.stop();
            }
        }
        assertFalse(Files.exists(path));
    }

    private static ServerSocket occupyPortWithAvailableSuccessor() throws IOException {
        for (int attempt = 0; attempt < 100; attempt++) {
            ServerSocket occupied = new ServerSocket();
            occupied.bind(new InetSocketAddress(HookEndpoint.LOOPBACK_HOST, 0));
            int successorPort = occupied.getLocalPort() + 1;
            if (successorPort > 65535) {
                occupied.close();
                continue;
            }

            try (ServerSocket successorProbe = new ServerSocket()) {
                successorProbe.bind(new InetSocketAddress(
                    HookEndpoint.LOOPBACK_HOST,
                    successorPort
                ));
                return occupied;
            } catch (IOException unavailableSuccessor) {
                occupied.close();
            }
        }
        throw new IOException("Could not reserve an occupied port with an available successor");
    }

    @Test
    void generatedScriptResolvesTheEndpointWithoutAFixedPort() {
        String script = HookEndpoint.powerShellScript();

        assertTrue(script.contains("$PSScriptRoot"));
        assertTrue(script.contains(HookEndpoint.ENDPOINT_FILE_NAME));
        assertTrue(script.contains("ConvertFrom-Json"));
        assertTrue(script.contains("Get-Process -Id $studioPid"));
        assertFalse(script.contains("8765"));
    }

    @Test
    void generatedPowerShellScriptDispatchesToThePublishedEndpointOnWindows() throws Exception {
        assumeTrue(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"));

        Path endpoint = temporaryDirectory.resolve(HookEndpoint.ENDPOINT_FILE_NAME);
        Path script = temporaryDirectory.resolve(HookEndpoint.SCRIPT_FILE_NAME);
        Files.writeString(script, "# stale managed script", StandardCharsets.UTF_8);

        AtomicInteger dispatchedState = new AtomicInteger(-1);
        BleManager testBleManager = new BleManager(null) {
            @Override
            public void updateState(byte state) {
                dispatchedState.set(Byte.toUnsignedInt(state));
            }
        };
        HookDispatchServer server = new HookDispatchServer(testBleManager, 0, endpoint);
        Process process = null;
        try {
            server.start();
            assertTrue(server.isRunning());
            assertTrue(Files.readString(script).contains(HookEndpoint.ENDPOINT_FILE_NAME));

            process = new ProcessBuilder(
                "powershell.exe",
                "-NoProfile",
                "-NonInteractive",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                script.toString(),
                "CodexStop"
            ).redirectErrorStream(true).start();
            process.getOutputStream().close();

            boolean exited = process.waitFor(10, TimeUnit.SECONDS);
            if (!exited) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();

            assertTrue(exited, () -> "PowerShell hook timed out: " + output);
            assertEquals(0, process.exitValue(), output);
            assertEquals("{}", output);
            assertTrue(server.getObservationSummary().contains("CodexStop"));
            assertEquals(IDEState.STOP.getCode(), dispatchedState.get());
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            server.stop();
        }
    }

    @Test
    void liveReadRejectsAnEndpointWhosePublisherExited() throws Exception {
        Path path = temporaryDirectory.resolve("stale-endpoint.json");
        Files.writeString(path, """
            {
              "schemaVersion": 1,
              "host": "127.0.0.1",
              "port": 18765,
              "processId": 9223372036854775807,
              "startedAt": "2026-09-04T00:00:00Z"
            }
            """);

        assertThrows(IOException.class, () -> HookEndpoint.readLive(path));
    }
}
