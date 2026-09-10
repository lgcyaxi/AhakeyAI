package com.example.ahakey.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/** Merge and remove only AhaKey-owned handlers in a Codex hooks.json tree. */
public final class CodexHookConfig {
    public static final String DESCRIPTION =
        "AhaKey Studio: Codex light-strip events and optional hardware approval.";

    private static final String COMMAND_MARKER = HookEndpoint.SCRIPT_FILE_NAME.toLowerCase(Locale.ROOT);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CodexHookConfig() {
    }

    public record Definition(
        String event,
        String matcher,
        String command,
        int timeoutSeconds,
        String statusMessage
    ) {
    }

    /** Read an existing config without silently replacing malformed user data. */
    public static ObjectNode read(Path path) throws IOException {
        if (!Files.exists(path)) {
            return MAPPER.createObjectNode();
        }
        JsonNode root = MAPPER.readTree(path.toFile());
        if (root == null || !root.isObject()) {
            throw new IOException("Codex hooks.json root must be a JSON object");
        }
        return (ObjectNode) root;
    }

    /** Atomically replace hooks.json after a successful merge. */
    public static void write(Path path, ObjectNode root) throws IOException {
        Path absolute = path.toAbsolutePath();
        Path parent = absolute.getParent();
        if (parent == null) {
            throw new IOException("Codex hooks.json path has no parent: " + path);
        }
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, "ahakey-hooks-", ".tmp");
        try {
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), root);
            try {
                Files.move(
                    temporary,
                    absolute,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public static ObjectNode install(ObjectNode source, List<Definition> definitions) {
        ObjectNode result = source.deepCopy();
        JsonNode existingHooks = result.get("hooks");
        if (existingHooks != null && !existingHooks.isObject()) {
            throw new IllegalArgumentException("Codex hooks must be a JSON object");
        }
        ObjectNode hooks = existingHooks == null
            ? result.objectNode()
            : (ObjectNode) existingHooks.deepCopy();

        // Remove legacy or current AhaKey handlers first so reinstall is idempotent.
        removeManagedHandlers(hooks);
        for (Definition definition : definitions) {
            ArrayNode groups = hooks.has(definition.event())
                ? requireArray(hooks.get(definition.event()), definition.event()).deepCopy()
                : hooks.arrayNode();
            groups.add(buildGroup(hooks, definition));
            hooks.set(definition.event(), groups);
        }
        result.set("hooks", hooks);
        if (!result.has("description")) {
            result.put("description", DESCRIPTION);
        }
        return result;
    }

    public static ObjectNode remove(ObjectNode source) {
        ObjectNode result = source.deepCopy();
        JsonNode hooksNode = result.get("hooks");
        if (hooksNode != null && hooksNode.isObject()) {
            ObjectNode hooks = (ObjectNode) hooksNode;
            removeManagedHandlers(hooks);
            if (hooks.isEmpty()) {
                result.remove("hooks");
            }
        }
        if (DESCRIPTION.equals(result.path("description").asText(null))) {
            result.remove("description");
        }
        return result;
    }

    public static boolean containsManagedHandler(ObjectNode root) {
        JsonNode hooksNode = root.get("hooks");
        if (hooksNode == null || !hooksNode.isObject()) {
            return false;
        }
        Iterator<JsonNode> events = hooksNode.elements();
        while (events.hasNext()) {
            JsonNode groups = events.next();
            if (!groups.isArray()) {
                continue;
            }
            for (JsonNode group : groups) {
                JsonNode handlers = group.path("hooks");
                if (!handlers.isArray()) {
                    continue;
                }
                for (JsonNode handler : handlers) {
                    if (isManagedHandler(handler)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static ObjectNode buildGroup(ObjectNode owner, Definition definition) {
        ObjectNode group = owner.objectNode();
        if (definition.matcher() != null && !definition.matcher().isBlank()) {
            group.put("matcher", definition.matcher());
        }
        ObjectNode handler = owner.objectNode();
        handler.put("type", "command");
        handler.put("command", definition.command());
        handler.put("timeout", definition.timeoutSeconds());
        handler.put("statusMessage", definition.statusMessage());
        ArrayNode handlers = owner.arrayNode();
        handlers.add(handler);
        group.set("hooks", handlers);
        return group;
    }

    private static void removeManagedHandlers(ObjectNode hooks) {
        List<String> eventNames = new ArrayList<>();
        hooks.fieldNames().forEachRemaining(eventNames::add);
        for (String eventName : eventNames) {
            JsonNode groupsNode = hooks.get(eventName);
            if (!groupsNode.isArray()) {
                continue;
            }
            ArrayNode filteredGroups = hooks.arrayNode();
            for (JsonNode groupNode : groupsNode) {
                if (!groupNode.isObject()) {
                    filteredGroups.add(groupNode.deepCopy());
                    continue;
                }
                ObjectNode group = (ObjectNode) groupNode.deepCopy();
                JsonNode handlersNode = group.get("hooks");
                if (handlersNode == null || !handlersNode.isArray()) {
                    filteredGroups.add(group);
                    continue;
                }
                ArrayNode filteredHandlers = hooks.arrayNode();
                for (JsonNode handler : handlersNode) {
                    if (!isManagedHandler(handler)) {
                        filteredHandlers.add(handler.deepCopy());
                    }
                }
                if (!filteredHandlers.isEmpty()) {
                    group.set("hooks", filteredHandlers);
                    filteredGroups.add(group);
                }
            }
            if (filteredGroups.isEmpty()) {
                hooks.remove(eventName);
            } else {
                hooks.set(eventName, filteredGroups);
            }
        }
    }

    private static boolean isManagedHandler(JsonNode handler) {
        String command = handler.path("command").asText("");
        return command.toLowerCase(Locale.ROOT).contains(COMMAND_MARKER);
    }

    private static ArrayNode requireArray(JsonNode node, String eventName) {
        if (!node.isArray()) {
            throw new IllegalArgumentException("Codex hook event must be an array: " + eventName);
        }
        return (ArrayNode) node;
    }
}
