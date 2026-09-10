package com.example.ahakey.model;

public enum ModeSlot {
    MODE0(0, "Profile 1", "Claude Code", "终端客户端"),
    MODE1(1, "Profile 2", "Claude Desktop", "桌面客户端"),
    MODE2(2, "Profile 3", "Codex CLI", "终端客户端"),
    MODE3(3, "Profile 4", "ChatGPT App", "桌面客户端");

    private final int index;
    private final String title;
    private final String shortName;
    private final String guidance;

    ModeSlot(int index, String title, String shortName, String guidance) {
        this.index = index;
        this.title = title;
        this.shortName = shortName;
        this.guidance = guidance;
    }

    public int getIndex() {
        return index;
    }

    public String getTitle() {
        return title;
    }

    public String getShortName() {
        return shortName;
    }

    public String getGuidance() {
        return guidance;
    }

    public static ModeSlot fromIndex(int index) {
        for (ModeSlot slot : values()) {
            if (slot.index == index) {
                return slot;
            }
        }
        return MODE0;
    }
}
