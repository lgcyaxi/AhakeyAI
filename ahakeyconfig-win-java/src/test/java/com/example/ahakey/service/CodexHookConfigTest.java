package com.example.ahakey.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodexHookConfigTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path temporaryDirectory;

    @Test
    void installIsIdempotentAndPreservesAnExistingHandler() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("description", "My existing hooks");
        ObjectNode hooks = root.putObject("hooks");
        ArrayNode groups = hooks.putArray("PreToolUse");
        ObjectNode customGroup = groups.addObject();
        customGroup.put("matcher", "Bash");
        customGroup.putArray("hooks")
            .addObject()
            .put("type", "command")
            .put("command", "python custom-policy.py");

        CodexHookConfig.Definition definition = new CodexHookConfig.Definition(
            "PreToolUse",
            "*",
            "powershell -File C:/hooks/ahakey-hook.ps1 CodexPreToolUse",
            20,
            "AhaKey Studio: 更新工具运行灯效"
        );

        ObjectNode installed = CodexHookConfig.install(root, List.of(definition));
        ObjectNode reinstalled = CodexHookConfig.install(installed, List.of(definition));

        assertEquals("My existing hooks", reinstalled.path("description").asText());
        assertEquals(2, reinstalled.path("hooks").path("PreToolUse").size());
        assertEquals(
            "python custom-policy.py",
            reinstalled.path("hooks").path("PreToolUse").get(0)
                .path("hooks").get(0).path("command").asText()
        );
        assertEquals(
            "AhaKey Studio: 更新工具运行灯效",
            reinstalled.path("hooks").path("PreToolUse").get(1)
                .path("hooks").get(0).path("statusMessage").asText()
        );
        assertTrue(CodexHookConfig.containsManagedHandler(reinstalled));
    }

    @Test
    void removeDeletesOnlyAhaKeyHandlers() {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode hooks = root.putObject("hooks");
        ObjectNode mixedGroup = hooks.putArray("Stop").addObject();
        ArrayNode handlers = mixedGroup.putArray("hooks");
        handlers.addObject()
            .put("type", "command")
            .put("command", "powershell -File C:/x/ahakey-hook.ps1 CodexStop");
        handlers.addObject()
            .put("type", "command")
            .put("command", "python preserve-me.py");

        ObjectNode removed = CodexHookConfig.remove(root);

        assertFalse(CodexHookConfig.containsManagedHandler(removed));
        assertEquals(1, removed.path("hooks").path("Stop").get(0).path("hooks").size());
        assertEquals(
            "python preserve-me.py",
            removed.path("hooks").path("Stop").get(0).path("hooks").get(0)
                .path("command").asText()
        );
    }

    @Test
    void ownedDescriptionIsRemovedWhenNoOtherContentExists() {
        ObjectNode root = CodexHookConfig.install(
            MAPPER.createObjectNode(),
            List.of(new CodexHookConfig.Definition(
                "Stop",
                null,
                "powershell -File C:/x/ahakey-hook.ps1 CodexStop",
                10,
                "AhaKey Studio: 更新任务停止灯效"
            ))
        );

        ObjectNode removed = CodexHookConfig.remove(root);

        assertTrue(removed.isEmpty());
    }

    @Test
    void malformedExistingConfigIsRejectedInsteadOfReplaced() throws Exception {
        Path path = temporaryDirectory.resolve("hooks.json");
        Files.writeString(path, "{ not-json");

        assertThrows(IOException.class, () -> CodexHookConfig.read(path));
        assertEquals("{ not-json", Files.readString(path));
    }

    @Test
    void atomicWriteRoundTripsMergedConfig() throws Exception {
        Path path = temporaryDirectory.resolve("hooks.json");
        ObjectNode root = MAPPER.createObjectNode();
        root.put("description", "preserve me");

        CodexHookConfig.write(path, root);

        assertEquals(root, CodexHookConfig.read(path));
    }
}
