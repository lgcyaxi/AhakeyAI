package com.example.ahakey.service.speech;

import com.example.ahakey.config.SpeechSettings;
import com.sun.jna.Platform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SpeechSettingsTest {
    @TempDir Path temporary;
    @Test void defaultsRemainLocalAndResourceIdsRejectHeaderInjection() throws Exception {
        SpeechSettings settings = new SpeechSettings(temporary.resolve("settings.json"));
        assertEquals(SpeechSettings.Provider.LOCAL, settings.getProvider());
        assertFalse(settings.hasCredentials());
        assertThrows(IllegalArgumentException.class, () -> settings.setAppId("id\r\nX-Header:bad"));
        assertThrows(IllegalArgumentException.class, () -> settings.setResourceId("other-provider"));
        settings.setProvider(SpeechSettings.Provider.DOUBAO);
        settings.setAppId("12345"); settings.save();
        assertEquals(SpeechSettings.Provider.DOUBAO,
            new SpeechSettings(temporary.resolve("settings.json")).getProvider());
    }
    @Test void windowsProtectsCredentialOnDiskAndReloadDecryptsForCurrentUser() throws Exception {
        assumeTrue(Platform.isWindows());
        Path file = temporary.resolve("settings.json");
        SpeechSettings settings = new SpeechSettings(file);
        settings.saveCredentials("unit-test-placeholder-not-a-real-token");
        assertFalse(Files.readString(file).contains("unit-test-placeholder"));
        assertEquals("unit-test-placeholder-not-a-real-token", new SpeechSettings(file).loadAccessKey());
        settings.clearCredentials();
        assertFalse(new SpeechSettings(file).hasCredentials());
    }
}
