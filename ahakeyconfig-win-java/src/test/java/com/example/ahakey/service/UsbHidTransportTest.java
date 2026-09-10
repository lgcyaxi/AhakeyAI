package com.example.ahakey.service;

import com.example.ahakey.protocol.AhaKeyProtocol;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UsbHidTransportTest {
    @Test
    void rejectsTheDjiMicrophoneReceiverVendorCollections() {
        String inputOnly = "\\\\?\\hid#vid_2ca3&pid_4011&mi_00&col03#input";
        String bidirectional = "\\\\?\\hid#vid_2ca3&pid_4011&mi_00&col04#vendor";

        assertNull(UsbHidTransport.findDeviceProfile(List.of(inputOnly, bidirectional)));
    }

    @Test
    void retainsTheLegacyReceiverProfile() {
        String legacy = "\\\\?\\hid#vid_413c&pid_2107&mi_01&col02#legacy";

        UsbHidTransport.DeviceProfile profile = UsbHidTransport.findDeviceProfile(List.of(legacy));

        assertNotNull(profile);
        assertEquals(legacy, profile.path());
        assertEquals((byte) 0, profile.reportId());
        assertEquals(65, profile.reportLength());
        assertTrue(profile.allowLegacyNoReportIdFallback());
    }

    @Test
    void buildsTheLegacyCommandReportLayout() throws Exception {
        UsbHidTransport.DeviceProfile profile = new UsbHidTransport.DeviceProfile(
            "legacy-col02", (byte) 0, 65, true
        );

        byte[] report = UsbHidTransport.buildCommandReport(profile, AhaKeyProtocol.queryDeviceStatus());

        assertEquals(65, report.length);
        assertArrayEquals(
            new byte[] {
                0, (byte) 0xA1, 0x05,
                (byte) 0xAA, (byte) 0xBB, 0x00, (byte) 0xCC, (byte) 0xDD
            },
            java.util.Arrays.copyOf(report, 8)
        );
    }

    @Test
    void acceptsOnlyACompletePlausibleDeviceStatusFrame() {
        byte[] validStatus = {
            (byte) 0xAA, (byte) 0xBB, 0x00,
            84, (byte) 0xD8, 1, 2, 1, 3, 0, 35,
            (byte) 0xCC, (byte) 0xDD
        };
        byte[] framedButNotStatus = {
            (byte) 0xAA, (byte) 0xBB, (byte) 0x90, 0,
            (byte) 0xCC, (byte) 0xDD
        };
        byte[] impossibleBattery = validStatus.clone();
        impossibleBattery[3] = 101;

        assertTrue(BleManager.isValidUsbStatusFrame(validStatus));
        assertFalse(BleManager.isValidUsbStatusFrame(framedButNotStatus));
        assertFalse(BleManager.isValidUsbStatusFrame(impossibleBattery));
    }
}
