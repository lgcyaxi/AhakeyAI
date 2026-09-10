package com.example.ahakey.service.speech;

import com.example.ahakey.service.ModelDownloadService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class ModelDownloadServiceTest {
    @TempDir Path temporary;
    @Test void rejectsUnverifiedImportAndLeavesExistingDestinationUntouched() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("source"));
        Path target = Files.createDirectory(temporary.resolve("models"));
        Files.writeString(source.resolve("model.int8.onnx"), "not-a-model");
        Files.writeString(source.resolve("tokens.txt"), "not-tokens");
        Files.writeString(target.resolve("model.int8.onnx"), "preserve-existing");
        ModelDownloadService downloads = new ModelDownloadService(target);
        assertFalse(downloads.isInstalled());
        assertThrows(java.io.IOException.class, () -> downloads.importFrom(source, ignored -> {}));
        assertEquals("preserve-existing", Files.readString(target.resolve("model.int8.onnx")));
        try (var files = Files.list(target)) { assertEquals(1, files.count()); }
    }
}
