package com.example.ahakey.model;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ProfileMigrationTest {
    @Test void newProfilesUseWechatAndHoldAndKeepExplicitChoicesOnReload() {
        var state = new StudioState();
        for (var mode : ModeSlot.values()) {
            assertEquals(VoicePreset.WECHAT, state.getKeyConfig(mode, StudioPart.KEY1).getVoicePreset());
            assertEquals(VoiceTriggerMode.PRESS_AND_HOLD, state.getKeyConfig(mode, StudioPart.KEY1).getVoiceTriggerMode());
        }
        var draft = state.toPersisted();
        draft.modes[0].voicePresetId = "WINDOWS_NATIVE";
        draft.modes[0].voiceTriggerModeId = "TOGGLE";
        state.loadFromPersisted(draft);
        assertEquals(VoicePreset.WINDOWS_NATIVE, state.getKeyConfig(ModeSlot.MODE0, StudioPart.KEY1).getVoicePreset());
        assertEquals(VoiceTriggerMode.TOGGLE, state.getKeyConfig(ModeSlot.MODE0, StudioPart.KEY1).getVoiceTriggerMode());
    }
    @Test void desktopProfilesConfirmWithEnterAndCliProfilesKeepTerminalKeys() {
        StudioState state = new StudioState();
        assertEquals("Claude Code", ModeSlot.MODE0.getShortName());
        assertEquals("Claude Desktop", ModeSlot.MODE1.getShortName());
        assertEquals("Codex CLI", ModeSlot.MODE2.getShortName());
        assertEquals("ChatGPT App", ModeSlot.MODE3.getShortName());
        for (ModeSlot mode : new ModeSlot[]{ModeSlot.MODE1, ModeSlot.MODE3}) {
            assertEquals(HIDUsage.ENTER, state.getKeyConfig(mode, StudioPart.KEY2).getHidCode());
            assertEquals(HIDUsage.ESCAPE, state.getKeyConfig(mode, StudioPart.KEY3).getHidCode());
        }
        assertEquals(HIDUsage.getCode("Y"), state.getKeyConfig(ModeSlot.MODE2, StudioPart.KEY2).getHidCode());
    }
    @Test void customAcceptSurvivesJsonUpgradeAndSecondLaunch() throws Exception {
        var draft = StudioState.PersistedDraft.defaults();
        draft.profileSchemaVersion = 0;
        draft.modes[1].oledSummary = "Cursor";
        draft.modes[1].key2Hid = 0x0228;
        draft.modes[1].key2Desc = "My shortcut";
        draft.modes[2].key2Hid = HIDUsage.ENTER;
        var first = new StudioState(); first.loadFromPersisted(draft);
        first.setSelectedMode(ModeSlot.MODE3);
        var mapper = new ObjectMapper();
        var roundTrip = mapper.readValue(mapper.writeValueAsString(first.toPersisted()), StudioState.PersistedDraft.class);
        var second = new StudioState(); second.loadFromPersisted(roundTrip);
        assertEquals(0x0228, second.getKeyConfig(ModeSlot.MODE1, StudioPart.KEY2).getHidCode());
        assertEquals(HIDUsage.ENTER, second.getKeyConfig(ModeSlot.MODE2, StudioPart.KEY2).getHidCode());
        assertEquals(2, second.toPersisted().profileSchemaVersion);
        assertEquals(ModeSlot.MODE3, second.getSelectedMode());
    }
    @Test void untouchedCursorDefaultsMigrateOnce() {
        var draft = StudioState.PersistedDraft.defaults();
        draft.profileSchemaVersion = 0; draft.modes[1].oledSummary = "Cursor";
        draft.modes[1].key2Hid = HIDUsage.getCode("Y"); draft.modes[1].key2Desc = "Yes";
        var state = new StudioState(); state.loadFromPersisted(draft);
        assertEquals(HIDUsage.ENTER, state.getKeyConfig(ModeSlot.MODE1, StudioPart.KEY2).getHidCode());
        assertEquals("Claude Desktop", state.toPersisted().modes[1].oledSummary);
    }
}
