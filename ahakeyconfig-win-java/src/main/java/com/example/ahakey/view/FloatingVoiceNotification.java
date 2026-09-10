package com.example.ahakey.view;

import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;
import javafx.stage.Screen;
import javafx.stage.Window;
import javafx.util.Duration;

/** Non-activating subtitles on the target screen, above its taskbar/work-area edge. */
public final class FloatingVoiceNotification {
    private final Popup popup = new Popup();
    private final Label status = new Label("准备录音");
    private final Label caption = new Label();
    private final VBox content = new VBox(7, status, caption);
    private final PauseTransition dismiss = new PauseTransition(Duration.seconds(6));
    private final Window owner;
    private Rectangle2D workArea;
    private boolean finalVisible;
    private boolean closed;
    private String currentStatus = "idle";

    public FloatingVoiceNotification(Window owner) {
        this.owner = owner;
        popup.setAutoFix(false);
        popup.setAutoHide(false);
        popup.setHideOnEscape(false);
        content.setFocusTraversable(false);
        content.setMouseTransparent(true);
        content.setStyle("-fx-background-color: rgba(24,36,32,0.96); -fx-background-radius: 16; -fx-padding: 14 20;");
        status.setStyle("-fx-text-fill: #adddcb; -fx-font-size: 11px; -fx-font-family: 'Segoe UI';");
        caption.setStyle("-fx-text-fill: white; -fx-font-size: 18px; -fx-font-family: 'Microsoft YaHei';");
        caption.setWrapText(true);
        caption.setMaxHeight(94);
        popup.getContent().add(content);
        dismiss.setOnFinished(e -> { finalVisible = false; popup.hide(); });
    }

    private static volatile java.util.Map<Long, Rectangle2D> monitorWorkAreas = java.util.Map.of();
    private static volatile Rectangle2D primaryWorkArea = new Rectangle2D(0, 0, 1280, 720);
    private static boolean screenTrackingInstalled;
    private record NativeMonitor(long id, com.sun.jna.platform.win32.WinUser.MONITORINFO info) {}

    public static void installScreenTracking() {
        if (screenTrackingInstalled) return;
        screenTrackingInstalled = true;
        refreshScreenMap();
        Screen.getScreens().addListener((javafx.collections.ListChangeListener<Screen>) change -> refreshScreenMap());
    }

    private static void refreshScreenMap() {
        primaryWorkArea = Screen.getPrimary().getVisualBounds();
        try {
            var nativeScreens = new java.util.ArrayList<NativeMonitor>();
            User32.INSTANCE.EnumDisplayMonitors(null, null, (monitor, dc, rectangle, parameter) -> {
                var info = new com.sun.jna.platform.win32.WinUser.MONITORINFO();
                if (User32.INSTANCE.GetMonitorInfo(monitor, info).booleanValue())
                    nativeScreens.add(new NativeMonitor(com.sun.jna.Pointer.nativeValue(monitor.getPointer()), info));
                return 1;
            }, new WinDef.LPARAM(0));
            // Pinned OpenJFX 17 uses native enumeration with the primary screen
            // swapped into index zero. Its DIP origins are synthetic; never
            // multiply a Screen origin by its DPI scale to recover rcMonitor.
            for (int i = 0; i < nativeScreens.size(); i++) {
                if ((nativeScreens.get(i).info().dwFlags & 1) != 0) {
                    java.util.Collections.swap(nativeScreens, 0, i); break;
                }
            }
            var fxScreens = java.util.List.copyOf(Screen.getScreens());
            if (nativeScreens.size() != fxScreens.size()) { monitorWorkAreas = java.util.Map.of(); return; }
            var mapped = new java.util.HashMap<Long, Rectangle2D>();
            for (int i = 0; i < fxScreens.size(); i++) {
                var fx = fxScreens.get(i); var win = nativeScreens.get(i);
                var full = win.info().rcMonitor; var work = win.info().rcWork;
                if (Math.abs(fx.getBounds().getWidth() * fx.getOutputScaleX() - (full.right - full.left)) > 3
                    || Math.abs(fx.getBounds().getHeight() * fx.getOutputScaleY() - (full.bottom - full.top)) > 3
                    || Math.abs(fx.getVisualBounds().getWidth() * fx.getOutputScaleX() - (work.right - work.left)) > 3
                    || Math.abs(fx.getVisualBounds().getHeight() * fx.getOutputScaleY() - (work.bottom - work.top)) > 3) {
                    monitorWorkAreas = java.util.Map.of(); return;
                }
                mapped.put(win.id(), fx.getVisualBounds());
            }
            monitorWorkAreas = java.util.Map.copyOf(mapped);
            org.slf4j.LoggerFactory.getLogger(FloatingVoiceNotification.class).info("Caption monitor map ready: {} screens", mapped.size());
        } catch (RuntimeException | LinkageError ex) { monitorWorkAreas = java.util.Map.of(); }
    }

    /** Physical key-down reads an immutable snapshot; no FX collections on the hook thread. */
    public static Rectangle2D foregroundWorkArea() {
        try {
            var foreground = User32.INSTANCE.GetForegroundWindow();
            var monitor = User32.INSTANCE.MonitorFromWindow(foreground, 2);
            if (monitor != null) {
                var area = monitorWorkAreas.get(com.sun.jna.Pointer.nativeValue(monitor.getPointer()));
                if (area != null) return area;
            }
            Platform.runLater(FloatingVoiceNotification::refreshScreenMap);
        } catch (RuntimeException | LinkageError ignored) {}
        return primaryWorkArea;
    }

    public void beginUtterance(Rectangle2D targetWorkArea) {
        onFx(() -> { workArea = targetWorkArea; finalVisible = false; dismiss.stop(); caption.setText("正在聆听…"); });
    }

    public void updateStatus(String code, String message) {
        onFx(() -> {
            if (closed || ("ready".equals(code) && finalVisible)) return;
            currentStatus = code;
            status.setText(switch (code) {
                case "recording" -> "正在录音 · 实时字幕";
                case "recognizing", "processing" -> "正在完成识别";
                case "starting" -> "正在准备语音引擎";
                case "error" -> "语音识别暂不可用";
                default -> message;
            });
            if ("recording".equals(code)) {
                dismiss.stop(); finalVisible = false;
                if (caption.getText().isBlank()) caption.setText("正在聆听…");
            }
            if ("idle".equals(code) || "stopped".equals(code) || "ready".equals(code)) popup.hide();
            else {
                if ("error".equals(code)) { caption.setText(message); dismiss.playFromStart(); }
                showNow();
            }
        });
    }

    public void showPartial(String value) {
        onFx(() -> {
            if (closed || finalVisible || !"recording".equals(currentStatus) || value == null || value.isBlank()) return;
            status.setText("正在录音 · 临时识别，结束后校正");
            caption.setText(tail(value.trim()));
            showNow();
        });
    }

    public void showResult(String value) {
        onFx(() -> {
            if (closed) return;
            finalVisible = true; currentStatus = "final";
            status.setText("识别完成");
            caption.setText(value == null || value.isBlank() ? "未识别到文字" : tail(value.trim()));
            showNow(); dismiss.playFromStart();
        });
    }

    private static String tail(String value) {
        int points = value.codePointCount(0, value.length());
        return points <= 110 ? value : "…" + value.substring(value.offsetByCodePoints(0, points - 110));
    }

    private void showNow() {
        if (closed || owner == null) return;
        if (workArea == null) workArea = foregroundWorkArea();
        double width = Math.min(620, workArea.getWidth() - 32);
        content.setPrefWidth(width);
        caption.setPrefWidth(width - 40);
        content.applyCss();
        content.layout();
        double height = content.prefHeight(width);
        Rectangle2D bounds = CaptionPlacement.place(workArea, width, height);
        if (!popup.isShowing()) popup.show(owner, bounds.getMinX(), bounds.getMinY());
        popup.setX(bounds.getMinX());
        popup.setY(bounds.getMinY());
    }

    public void show() { onFx(this::showNow); }
    public void hide() { onFx(popup::hide); }
    public void close() { onFx(() -> { closed = true; dismiss.stop(); popup.hide(); }); }
    private static void onFx(Runnable action) {
        if (Platform.isFxApplicationThread()) action.run(); else Platform.runLater(action);
    }
}
