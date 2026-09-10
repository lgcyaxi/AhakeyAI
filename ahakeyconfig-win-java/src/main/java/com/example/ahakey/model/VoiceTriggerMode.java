package com.example.ahakey.model;

import java.util.List;

/** How a Studio-managed voice provider reacts to the physical voice key. */
public enum VoiceTriggerMode {
    TOGGLE("按一下开始/停止"),
    PRESS_AND_HOLD("按住说话（松开停止）");

    private final String displayName;

    VoiceTriggerMode(String displayName) {
        this.displayName = displayName;
    }

    public static List<VoiceTriggerMode> windowsOptions() {
        return List.of(PRESS_AND_HOLD, TOGGLE);
    }

    /**
     * New or missing preferences use press-and-hold. Explicit saved choices
     * are loaded separately and must not be reset on each launch.
     */
    public static VoiceTriggerMode defaultFor(VoicePreset preset) {
        return PRESS_AND_HOLD;
    }

    public boolean stopsOnRelease() {
        return this == PRESS_AND_HOLD;
    }

    public String getDetail(VoicePreset preset) {
        if (this == TOGGLE) {
            return "按一次开始，再按一次结束。";
        }
        if (preset == VoicePreset.WINDOWS_NATIVE) {
            return "按下时启动 Windows 语音输入，松开时再次发送 Win+H 结束。";
        }
        if (preset == VoicePreset.WECHAT) {
            return "按下时触发一次微信语音，松开时再触发一次结束并上屏；需要 AhaKey Studio 常驻。";
        }
        return "按下时开始本地录音，松开后立即停止并转写。";
    }

    @Override
    public String toString() {
        return displayName;
    }
}
