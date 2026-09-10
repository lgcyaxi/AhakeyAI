package com.example.ahakey.service;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;

/**
 * Publishes the loopback endpoint owned by the current Studio process and
 * provides the managed PowerShell dispatcher used by editor hooks.
 */
public final class HookEndpoint {
    public static final String LOOPBACK_HOST = "127.0.0.1";
    public static final String SCRIPT_FILE_NAME = "ahakey-hook.ps1";
    public static final String ENDPOINT_FILE_NAME = "active-endpoint.json";

    private static final int SCHEMA_VERSION = 1;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HookEndpoint() {
    }

    public record Descriptor(
        int schemaVersion,
        String host,
        int port,
        long processId,
        String startedAt
    ) {
    }

    public static Path hooksDirectory() {
        return Path.of(System.getProperty("user.home"), ".ahakey", "hooks");
    }

    public static Path scriptPath() {
        return hooksDirectory().resolve(SCRIPT_FILE_NAME);
    }

    public static Path endpointPath() {
        return hooksDirectory().resolve(ENDPOINT_FILE_NAME);
    }

    public static Descriptor publish(Path path, int port) throws IOException {
        validatePort(port);
        Path parent = path.toAbsolutePath().getParent();
        if (parent == null) {
            throw new IOException("Hook endpoint path has no parent: " + path);
        }
        Files.createDirectories(parent);

        Descriptor descriptor = new Descriptor(
            SCHEMA_VERSION,
            LOOPBACK_HOST,
            port,
            ProcessHandle.current().pid(),
            Instant.now().toString()
        );
        Path temporary = Files.createTempFile(parent, "active-endpoint-", ".tmp");
        try {
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), descriptor);
            try {
                Files.move(
                    temporary,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        return descriptor;
    }

    public static Descriptor read(Path path) throws IOException {
        Descriptor descriptor = MAPPER.readValue(path.toFile(), Descriptor.class);
        if (descriptor.schemaVersion() != SCHEMA_VERSION) {
            throw new IOException("Unsupported Hook endpoint schema: " + descriptor.schemaVersion());
        }
        if (!LOOPBACK_HOST.equals(descriptor.host())) {
            throw new IOException("Hook endpoint is not loopback");
        }
        validatePort(descriptor.port());
        if (descriptor.processId() <= 0 || descriptor.startedAt() == null || descriptor.startedAt().isBlank()) {
            throw new IOException("Hook endpoint ownership metadata is incomplete");
        }
        return descriptor;
    }

    public static Descriptor readCurrent() throws IOException {
        return read(endpointPath());
    }

    public static Descriptor readLive(Path path) throws IOException {
        Descriptor descriptor = read(path);
        boolean alive = ProcessHandle.of(descriptor.processId())
            .map(ProcessHandle::isAlive)
            .orElse(false);
        if (!alive) {
            throw new IOException("Hook endpoint publisher is not running");
        }
        return descriptor;
    }

    public static Descriptor readLiveCurrent() throws IOException {
        return readLive(endpointPath());
    }

    public static void clearIfOwned(Path path, Descriptor owner) throws IOException {
        if (owner == null || !Files.exists(path)) {
            return;
        }
        Descriptor current;
        try {
            current = read(path);
        } catch (IOException invalidOrPartial) {
            return;
        }
        if (owner.equals(current)) {
            Files.deleteIfExists(path);
        }
    }

    public static Path installPowerShellScript() throws IOException {
        Path path = scriptPath();
        Files.createDirectories(path.getParent());
        Files.writeString(path, powerShellScript(), StandardCharsets.UTF_8);
        return path;
    }

    public static boolean refreshPowerShellScriptIfPresent() throws IOException {
        return refreshPowerShellScriptIfPresent(scriptPath());
    }

    static boolean refreshPowerShellScriptIfPresent(Path path) throws IOException {
        if (!Files.exists(path)) {
            return false;
        }
        Files.writeString(path, powerShellScript(), StandardCharsets.UTF_8);
        return true;
    }

    static String powerShellScript() {
        return """
            # AhaKey Hook Dispatcher - Auto-generated, do not edit
            # Resolves the active AhaKey Studio loopback endpoint for each event.
            param([Parameter(Position=0)][string]$EventName)
            try {
                if ([Console]::IsInputRedirected) { $null = [Console]::In.ReadToEnd() }
            } catch { }

            $response = $null
            $tcp = $null
            $writer = $null
            $reader = $null
            try {
                $endpointPath = Join-Path $PSScriptRoot 'active-endpoint.json'
                if (-not (Test-Path -LiteralPath $endpointPath)) {
                    throw 'AhaKey Studio endpoint is not published'
                }
                $endpoint = Get-Content -Raw -LiteralPath $endpointPath | ConvertFrom-Json
                $hostName = [string]$endpoint.host
                $port = [int]$endpoint.port
                $studioPid = [long]$endpoint.processId
                if ($hostName -ne '127.0.0.1' -or $port -lt 1 -or $port -gt 65535) {
                    throw 'AhaKey Studio endpoint is invalid'
                }
                $studioProcess = Get-Process -Id $studioPid -ErrorAction SilentlyContinue
                if ($studioPid -le 0 -or $null -eq $studioProcess) {
                    throw 'AhaKey Studio endpoint publisher is not running'
                }

                $tcp = New-Object System.Net.Sockets.TcpClient
                $connectTask = $tcp.ConnectAsync($hostName, $port)
                if (-not $connectTask.Wait(1500)) {
                    throw 'Timed out connecting to AhaKey Studio'
                }
                $stream = $tcp.GetStream()
                $stream.ReadTimeout = 2000
                $stream.WriteTimeout = 2000
                $writer = New-Object System.IO.StreamWriter($stream)
                $writer.WriteLine($EventName)
                $writer.Flush()
                $reader = New-Object System.IO.StreamReader($stream)
                $response = $reader.ReadLine()
            } catch {
                $response = $null
            } finally {
                if ($reader) { $reader.Dispose() }
                if ($writer) { $writer.Dispose() }
                if ($tcp) { $tcp.Dispose() }
            }

            # Codex lifecycle hooks must output valid event-specific JSON.
            if ($EventName -match '^Codex' -and $EventName -ne 'CodexPermissionRequest') {
                [Console]::WriteLine('{}')
                exit 0
            }
            # A missing Studio response intentionally declines to decide.
            if ($EventName -eq 'CodexPermissionRequest') {
                $isAuto = $response -match '"autoApproved"\\s*:\\s*true'
                if ($isAuto) {
                    [Console]::WriteLine('{"hookSpecificOutput":{"hookEventName":"PermissionRequest","decision":{"behavior":"allow"}}}')
                } else {
                    [Console]::WriteLine('{"hookSpecificOutput":{"hookEventName":"PermissionRequest"}}')
                }
                exit 0
            }
            if ($EventName -eq 'PermissionRequest') {
                $isAuto = $response -match '"autoApproved"\\s*:\\s*true'
                if ($isAuto) {
                    [Console]::WriteLine('{"hookSpecificOutput":{"hookEventName":"PermissionRequest","decision":{"behavior":"allow"}}}')
                } else {
                    [Console]::WriteLine('{"hookSpecificOutput":{"hookEventName":"PermissionRequest","decision":{"behavior":"ask"}}}')
                }
                exit 0
            }
            if ($response) {
                [Console]::WriteLine($response)
            } else {
                [Console]::WriteLine('{"ok":true}')
            }
            exit 0
            """;
    }

    private static void validatePort(int port) throws IOException {
        if (port < 1 || port > 65535) {
            throw new IOException("Invalid Hook endpoint port: " + port);
        }
    }
}
