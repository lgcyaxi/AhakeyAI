package com.example.ahakey.service;

import com.example.ahakey.model.ModeSlot;
import com.example.ahakey.model.StudioPart;
import com.example.ahakey.model.StudioState;
import com.example.ahakey.model.VoicePreset;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoicePresetSyncTest {
    @Test
    void weChatPresetSerializesLeftCtrlShiftAndWinWithoutABaseKey() {
        StudioState state = new StudioState();
        var key = state.getKeyConfig(ModeSlot.MODE2, StudioPart.KEY1);
        key.setVoicePreset(VoicePreset.WECHAT);
        key.setHidCode(VoicePreset.WECHAT.windowsHidCode(ModeSlot.MODE2, key.getHidCode()));

        byte[] command = DeviceSyncService.commandsForModes(state, ModeSlot.MODE2).get(0).data();

        assertEquals(VoicePreset.WECHAT_HID_CODE, key.getHidCode());
        assertArrayEquals(
            new byte[] {
                (byte) 0xAA, (byte) 0xBB, 0x73,
                0x73, 0x02, 0x00,
                (byte) 0xE0, (byte) 0xE1, (byte) 0xE3,
                (byte) 0xCC, (byte) 0xDD
            },
            command
        );
    }

    @Test
    void nativeAndLocalProvidersRetainThePerModeFunctionKeys() {
        assertEquals(0x6C, VoicePreset.WINDOWS_NATIVE.windowsHidCode(ModeSlot.MODE1, 0));
        assertEquals(0x6D, VoicePreset.LOCAL_MODEL.windowsHidCode(ModeSlot.MODE2, 0));
    }

    @Test
    void legacyCustomFunctionKeyLoadsAsWindowsVoiceTyping() {
        StudioState.PersistedDraft draft = StudioState.PersistedDraft.defaults();
        var legacyMode = draft.modes[ModeSlot.MODE2.getIndex()];
        legacyMode.voicePresetId = VoicePreset.CUSTOM.name();
        legacyMode.key1Hid = com.example.ahakey.model.HIDUsage.F18;

        StudioState state = new StudioState();
        state.loadFromPersisted(draft);

        var migrated = state.getKeyConfig(ModeSlot.MODE2, StudioPart.KEY1);
        assertEquals(VoicePreset.WINDOWS_NATIVE, migrated.getVoicePreset());
        assertEquals(com.example.ahakey.model.HIDUsage.F18, migrated.getHidCode());
        assertTrue(state.isDirty(StudioPart.KEY1));
        assertTrue(state.syncStatusProperty().get().contains("已迁移旧版 Key1"));
    }

    @Test
    void persistedWeChatPresetNormalizesItsShortcutAndNeedsSave() {
        StudioState.PersistedDraft draft = StudioState.PersistedDraft.defaults();
        var weChatMode = draft.modes[ModeSlot.MODE2.getIndex()];
        weChatMode.voicePresetId = VoicePreset.WECHAT.name();
        weChatMode.key1Hid = com.example.ahakey.model.HIDUsage.F18;

        StudioState state = new StudioState();
        state.loadFromPersisted(draft);

        var migrated = state.getKeyConfig(ModeSlot.MODE2, StudioPart.KEY1);
        assertEquals(VoicePreset.WECHAT, migrated.getVoicePreset());
        assertEquals(VoicePreset.WECHAT_HID_CODE, migrated.getHidCode());
        assertTrue(state.isDirty(StudioPart.KEY1));
    }

    @Test
    void intentionalCustomShortcutRemainsCustom() {
        int hidA = 0x04;
        StudioState.PersistedDraft draft = StudioState.PersistedDraft.defaults();
        var customMode = draft.modes[ModeSlot.MODE2.getIndex()];
        customMode.voicePresetId = VoicePreset.CUSTOM.name();
        customMode.key1Hid = hidA;

        StudioState state = new StudioState();
        state.loadFromPersisted(draft);

        var loaded = state.getKeyConfig(ModeSlot.MODE2, StudioPart.KEY1);
        assertEquals(VoicePreset.CUSTOM, loaded.getVoicePreset());
        assertEquals(hidA, loaded.getHidCode());
        assertFalse(state.isDirty(StudioPart.KEY1));
    }
}
