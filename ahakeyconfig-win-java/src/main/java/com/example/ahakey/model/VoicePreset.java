package com.example.ahakey.model;

import java.util.List;

/** Key1 voice provider preset. The selected preset is authoritative. */
public enum VoicePreset {
    CUSTOM("自定义快捷键", false),
    WINDOWS_NATIVE("Windows 语音 (Win+H)", true),
    LOCAL_MODEL("AhaKey 本地离线模型（需启用）", true),
    MACOS_NATIVE("macOS 原生语音", true),
    TYPELESS("Typeless / Fn", true),
    WECHAT("微信输入法语音（推荐）", true);

    /** Internal shortcut-mask encoding: Left Ctrl + Left Shift + Left Win. */
    public static final int WECHAT_HID_CODE = 0x0B00;

    private final String displayName;
    private final boolean locksShortcut;

    VoicePreset(String displayName, boolean locksShortcut) {
        this.displayName = displayName;
        this.locksShortcut = locksShortcut;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean locksShortcut() {
        return locksShortcut;
    }

    public static List<VoicePreset> windowsOptions() {
        return List.of(WECHAT, WINDOWS_NATIVE, LOCAL_MODEL, CUSTOM);
    }

    /**
     * Return the device HID value used by this preset on Windows. Custom and
     * unsupported cross-platform presets retain the current binding.
     */
    public int windowsHidCode(ModeSlot mode, int currentHidCode) {
        return switch (this) {
            case WECHAT -> WECHAT_HID_CODE;
            case WINDOWS_NATIVE, LOCAL_MODEL ->
                mode == ModeSlot.MODE1 ? HIDUsage.F17 : HIDUsage.F18;
            case CUSTOM, MACOS_NATIVE, TYPELESS -> currentHidCode;
        };
    }

    public String getDetail() {
        return switch (this) {
            case WINDOWS_NATIVE ->
                "AhaKey Studio 在后台拦截 F17/F18 并发送 Win+H，使用 Windows 自带语音输入。文字进入当前有光标的文本框，不会自动切换到 Codex。";
            case LOCAL_MODEL ->
                "由 AhaKey Studio 录音并用本地模型转写，再把文字注入当前文本框。仅在应用包的 model_config.properties 启用 model.enabled 且包含模型文件时可用；不会自动切换应用。";
            case MACOS_NATIVE ->
                "仅 macOS 完整支持；Windows 请改用「Windows 语音 (Win+H)」。";
            case TYPELESS ->
                "Windows 版暂未实现 Fn 注入；请改用其他语音方式。";
            case WECHAT ->
                "把 Ctrl+Shift+Win 直接写入 Key1，由微信输入法全局接收。保存到键盘后无需 Studio 常驻；文字仍进入当前有光标的文本框。";
            case CUSTOM ->
                "自行绑定 HID 单键或组合键。按键发送给当前活动应用，不会自动寻找或聚焦 Codex。";
        };
    }

    @Override
    public String toString() {
        return displayName;
    }
}
