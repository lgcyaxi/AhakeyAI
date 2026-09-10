package com.example.ahakey.view;

import com.example.ahakey.app.StudioController;
import com.example.ahakey.model.*;
import com.example.ahakey.service.VoiceInputManager;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.util.StringConverter;

/** Transitional desktop navigation: everyday voice first, advanced hardware editing on demand. */
public final class StudioShell extends BorderPane implements AutoCloseable {
    private final VoiceSettingsPane settings;
    private final StudioController controller;
    private final TopBar topBar;
    private final Node deviceEditor;
    private final VBox devicePage;
    private final ToggleGroup navigation = new ToggleGroup();
    private final java.util.Map<String, ToggleButton> entries = new java.util.HashMap<>();

    public StudioShell(StudioController controller, TopBar topBar, Node editor, VoiceInputManager voice) {
        this.controller = controller; this.topBar = topBar; this.deviceEditor = editor;
        getStyleClass().add("studio-shell");
        settings = new VoiceSettingsPane(voice, topBar::refreshVoiceAvailability);
        devicePage = new VBox(16, pageTitle("设备与键盘"), new DeviceConnectionPane(controller), editor);
        devicePage.setPadding(new Insets(24));
        devicePage.setMinWidth(0);
        VBox rail = new VBox(8); rail.getStyleClass().add("studio-rail"); rail.setPrefWidth(168); rail.setMinWidth(144);
        Label brand = new Label("工作空间"); brand.getStyleClass().add("nav-caption");
        rail.getChildren().add(brand);
        for (String name : new String[]{"语音", "设备", "Hook 与灯效", "设置"}) {
            ToggleButton button = new ToggleButton(name); button.setMaxWidth(Double.MAX_VALUE);
            button.getStyleClass().add("nav-button"); button.setToggleGroup(navigation);
            button.setOnAction(e -> showPage(name)); entries.put(name, button); rail.getChildren().add(button);
        }
        Region grow = new Region(); VBox.setVgrow(grow, Priority.ALWAYS);
        Label note = new Label("JavaFX 过渡版\n统一客户端同步开发"); note.setWrapText(true); note.getStyleClass().add("shell-muted");
        rail.getChildren().addAll(grow, note); setLeft(rail);
        topBar.setOpenDeviceSettings(() -> showPage("设备"));
        showPage("语音");
    }

    private Node voicePage() {
        var state = controller.getStudioState();
        ComboBox<ModeSlot> profiles = new ComboBox<>(); profiles.getItems().setAll(ModeSlot.values());
        profiles.setConverter(new StringConverter<>() {
            public String toString(ModeSlot m) { return m == null ? "" : (m.getIndex() + 1) + " · " + m.getShortName(); }
            public ModeSlot fromString(String s) { return null; }
        });
        profiles.setValue(state.getSelectedMode());
        ComboBox<VoicePreset> providers = new ComboBox<>(); providers.getItems().setAll(VoicePreset.windowsOptions());
        ComboBox<VoiceTriggerMode> triggers = new ComboBox<>(); triggers.getItems().setAll(VoiceTriggerMode.windowsOptions());
        Runnable update = () -> { var key = state.getKeyConfig(StudioPart.KEY1); providers.setValue(key.getVoicePreset()); triggers.setValue(key.getVoiceTriggerMode()); };
        update.run();
        profiles.setOnAction(e -> { state.setSelectedMode(profiles.getValue()); update.run(); });
        providers.setOnAction(e -> { if (providers.getValue() != null) controller.applyVoicePreset(providers.getValue()); });
        triggers.setOnAction(e -> { if (triggers.getValue() != null) controller.applyVoiceTriggerMode(triggers.getValue()); });
        for (ComboBox<?> combo : java.util.List.of(profiles, providers, triggers)) {
            combo.setMinWidth(0);
            combo.setMaxWidth(Double.MAX_VALUE);
        }
        Button setup = new Button("模型与云端设置"); setup.getStyleClass().add("shell-secondary");
        setup.setOnAction(e -> showPage("设置"));
        Label detail = new Label("选择当前键盘 profile，并保存配置写入键盘。AhaKey 语音支持本地或豆包；微信和 Win+H 继续使用各自的输入服务。");
        detail.setWrapText(true); detail.getStyleClass().add("shell-muted");
        GridPane form = new GridPane(); form.setHgap(16); form.setVgap(8);
        VBox[] fields = {voiceField("Profile", profiles), voiceField("语音方式", providers), voiceField("按键行为", triggers)};
        form.getChildren().addAll(fields);
        Runnable reflow = () -> {
            int columns = form.getWidth() >= 650 ? 3 : 1;
            if (form.getColumnConstraints().size() == columns) return;
            form.getColumnConstraints().clear();
            for (int i = 0; i < columns; i++) {
                ColumnConstraints column = new ColumnConstraints();
                column.setMinWidth(0); column.setPercentWidth(100.0 / columns);
                form.getColumnConstraints().add(column);
            }
            for (int i = 0; i < fields.length; i++) {
                GridPane.setColumnIndex(fields[i], i % columns);
                GridPane.setRowIndex(fields[i], i / columns);
            }
        };
        form.widthProperty().addListener((obs, before, after) -> reflow.run());
        reflow.run();
        VBox card = new VBox(14, form, detail, setup); card.getStyleClass().add("studio-card");
        Label focus = new Label("文字输入到当前有光标的文本框。字幕显示在该窗口所在屏幕的任务栏上方。");
        focus.setWrapText(true); focus.getStyleClass().add("shell-muted");
        VBox page = new VBox(22, pageTitle("把想法说出来"), focus, card, topBar.getVoiceWorkspace());
        page.getStyleClass().add("studio-page"); return page;
    }

    private Node integrations() {
        Label status = new Label(controller.isHookDispatchRunning()
            ? "Hook 服务已运行 · 本机端口 " + controller.getHookDispatchPort() : "Hook 服务尚未运行");
        Label last = new Label(controller.getHookObservationSummary()); last.setWrapText(true);
        Button manage = new Button("管理 Hook 与最近事件"); manage.getStyleClass().add("shell-primary");
        manage.setOnAction(e -> topBar.showIntegrations());
        Button lights = new Button("编辑键盘灯效"); lights.getStyleClass().add("shell-secondary");
        lights.setOnAction(e -> { controller.getStudioState().setSelectedPart(StudioPart.LIGHT_BAR); showPage("设备"); });
        VBox card = new VBox(16, status, last, new HBox(12, manage, lights)); card.getStyleClass().add("studio-card");
        Label note = new Label("Hook 服务状态与键盘连接分别检查。桌面 App 和 CLI 的批准按键可以在设备配置中单独设置。");
        note.setWrapText(true); note.getStyleClass().add("shell-muted");
        VBox page = new VBox(22, pageTitle("Hook 与灯效"), note, card); page.getStyleClass().add("studio-page"); return page;
    }

    private void showPage(String name) {
        entries.get(name).setSelected(true);
        setCenter(null);
        Node content = switch (name) {
            case "设备" -> devicePage;
            case "设置" -> settings;
            case "Hook 与灯效" -> integrations();
            default -> voicePage();
        };
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("page-scroll");
        setCenter(scroll);
    }
    private static VBox voiceField(String title, ComboBox<?> control) {
        Label label = new Label(title); label.setLabelFor(control);
        VBox field = new VBox(8, label, control); field.setMinWidth(0); return field;
    }
    private static Label pageTitle(String title) { Label label = new Label(title); label.getStyleClass().add("page-title"); return label; }
    public void close() { settings.close(); }
}
