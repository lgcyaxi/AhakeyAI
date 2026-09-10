package com.example.ahakey.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoiceInputManagerModelDiscoveryTest {

    @TempDir
    Path tempDirectory;

    @Test
    void applicationModelPairWinsOverWorkingDirectory() throws Exception {
        Path applicationDirectory = tempDirectory.resolve("image").resolve("app");
        Path workingDirectory = tempDirectory.resolve("working");
        Path appModels = Files.createDirectories(applicationDirectory.resolve("models"));
        Path workingModels = Files.createDirectories(workingDirectory.resolve("models"));

        Files.writeString(appModels.resolve("model.int8.onnx"), "app-model");
        Files.writeString(appModels.resolve("tokens.txt"), "app-tokens");
        Files.writeString(workingModels.resolve("model.int8.onnx"), "working-model");
        Files.writeString(workingModels.resolve("tokens.txt"), "working-tokens");

        VoiceInputManager.ModelFiles files = VoiceInputManager.findModelFiles(
            "models/model.int8.onnx",
            "models/tokens.txt",
            applicationDirectory,
            workingDirectory
        );

        assertEquals(appModels.resolve("model.int8.onnx"), files.model());
        assertEquals(appModels.resolve("tokens.txt"), files.tokens());
    }

    @Test
    void incompleteApplicationPairDoesNotMixWithAnotherRoot() throws Exception {
        Path applicationDirectory = tempDirectory.resolve("image").resolve("app");
        Path workingDirectory = tempDirectory.resolve("working");
        Path appModels = Files.createDirectories(applicationDirectory.resolve("models"));
        Path workingModels = Files.createDirectories(workingDirectory.resolve("models"));

        Files.writeString(appModels.resolve("model.int8.onnx"), "orphan-model");
        Files.writeString(workingModels.resolve("model.int8.onnx"), "working-model");
        Files.writeString(workingModels.resolve("tokens.txt"), "working-tokens");

        VoiceInputManager.ModelFiles files = VoiceInputManager.findModelFiles(
            "models/model.int8.onnx",
            "models/tokens.txt",
            applicationDirectory,
            workingDirectory
        );

        assertEquals(workingModels.resolve("model.int8.onnx"), files.model());
        assertEquals(workingModels.resolve("tokens.txt"), files.tokens());
    }

    @Test
    void missingPairReportsEveryFilesystemLocation() throws Exception {
        Path applicationDirectory = tempDirectory.resolve("image").resolve("app");
        Path workingDirectory = tempDirectory.resolve("working");

        Exception error = assertThrows(
            Exception.class,
            () -> VoiceInputManager.findModelFiles(
                "models/model.int8.onnx",
                "models/tokens.txt",
                applicationDirectory,
                workingDirectory
            )
        );

        assertTrue(error.getMessage().contains(applicationDirectory.toString()));
        assertTrue(error.getMessage().contains(workingDirectory.toString()));
        assertTrue(error.getMessage().contains("model.int8.onnx"));
        assertTrue(error.getMessage().contains("tokens.txt"));
        assertTrue(!error.getMessage().contains("jar:file:"));
    }
}
