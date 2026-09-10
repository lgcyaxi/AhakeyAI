package com.example.ahakey.service;

import com.example.ahakey.model.ModeSlot;
import com.example.ahakey.model.StudioPart;
import com.example.ahakey.model.StudioState;
import com.example.ahakey.model.VoicePreset;
import com.example.ahakey.model.VoiceTriggerMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoicePresetSyncTest {
    @Test
    void weChatPresetSerializesThePerModeStudioRelayKey() {
        StudioState state = new StudioState();
        var key = state.getKeyConfig(ModeSlot.MODE2, StudioPart.KEY1);
        key.setVoicePreset(VoicePreset.WECHAT);
        key.setHidCode(VoicePreset.WECHAT.windowsHidCode(ModeSlot.MODE2, key.getHidCode()));

        byte[] command = DeviceSyncService.commandsForModes(state, ModeSlot.MODE2).get(0).data();

        assertEquals(com.example.ahakey.model.HIDUsage.F18, key.getHidCode());
        assertArrayEquals(
            new byte[] {
                (byte) 0xAA, (byte) 0xBB, 0x73,
                0x73, 0x02, 0x00, 0x6D,
                (byte) 0xCC, (byte) 0xDD
            },
            command
        );
    }

    @Test
    void nativeAndLocalProvidersRetainThePerModeFunctionKeys() {
        assertEquals(0x6C, VoicePreset.WINDOWS_NATIVE.windowsHidCode(ModeSlot.MODE1, 0));
        assertEquals(0x6D, VoicePreset.LOCAL_MODEL.windowsHidCode(ModeSlot.MODE2, 0));
        assertEquals(0x6D, VoicePreset.WECHAT.windowsHidCode(ModeSlot.MODE2, 0));
        assertTrue(VoicePreset.WECHAT.isStudioManagedOnWindows());
    }

    @Test
    void missingTriggerPreferenceUsesHoldForEachProvider() {
        StudioState.PersistedDraft draft = StudioState.PersistedDraft.defaults();
        var nativeMode = draft.modes[ModeSlot.MODE1.getIndex()];
        nativeMode.voicePresetId = VoicePreset.WINDOWS_NATIVE.name();
        nativeMode.voiceTriggerModeId = null;
        var localMode = draft.modes[ModeSlot.MODE2.getIndex()];
        localMode.voicePresetId = VoicePreset.LOCAL_MODEL.name();
        localMode.voiceTriggerModeId = null;

        StudioState state = new StudioState();
        state.loadFromPersisted(draft);

        assertEquals(
            VoiceTriggerMode.PRESS_AND_HOLD,
            state.getKeyConfig(ModeSlot.MODE1, StudioPart.KEY1).getVoiceTriggerMode()
        );
        assertEquals(
            VoiceTriggerMode.PRESS_AND_HOLD,
            state.getKeyConfig(ModeSlot.MODE2, StudioPart.KEY1).getVoiceTriggerMode()
        );
    }

    @Test
    void pressAndHoldTriggerRoundTripsIndependentlyFromProvider() {
        StudioState state = new StudioState();
        var key = state.getKeyConfig(ModeSlot.MODE2, StudioPart.KEY1);
        key.setVoicePreset(VoicePreset.WINDOWS_NATIVE);
        key.setVoiceTriggerMode(VoiceTriggerMode.PRESS_AND_HOLD);

        StudioState restored = new StudioState();
        restored.loadFromPersisted(state.toPersisted());

        var loaded = restored.getKeyConfig(ModeSlot.MODE2, StudioPart.KEY1);
        assertEquals(VoicePreset.WINDOWS_NATIVE, loaded.getVoicePreset());
        assertEquals(VoiceTriggerMode.PRESS_AND_HOLD, loaded.getVoiceTriggerMode());
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
        weChatMode.key1Hid = VoicePreset.WECHAT_HID_CODE;

        StudioState state = new StudioState();
        state.loadFromPersisted(draft);

        var migrated = state.getKeyConfig(ModeSlot.MODE2, StudioPart.KEY1);
        assertEquals(VoicePreset.WECHAT, migrated.getVoicePreset());
        assertEquals(com.example.ahakey.model.HIDUsage.F18, migrated.getHidCode());
        assertEquals(VoiceTriggerMode.PRESS_AND_HOLD, migrated.getVoiceTriggerMode());
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
