package com.example.ahakey.view;

import com.example.ahakey.config.SpeechSettings;
import com.example.ahakey.service.ModelDownloadService;
import com.example.ahakey.service.VoiceInputManager;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

/** Explicit provider activation, credential storage and opt-in model download. */
public final class VoiceSettingsPane extends VBox implements AutoCloseable {
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "voice-settings"); t.setDaemon(true); return t;
    });
    private final Label feedback = new Label();
    private final Label modelStatus = new Label();
    private final ProgressBar progress = new ProgressBar(0);
    private final ModelDownloadService models = new ModelDownloadService();
    private final SpeechSettings settings = SpeechSettings.getInstance();
    private volatile boolean closed;

    public VoiceSettingsPane(VoiceInputManager manager, Runnable refresh) {
        super(18);
        getStyleClass().add("studio-page");
        Label title = new Label("语音设置"); title.getStyleClass().add("page-title");
        Label lead = new Label("引擎已内置。选择本地识别时再下载权重，或配置豆包 API 使用云端识别。");
        lead.setWrapText(true); lead.getStyleClass().add("shell-muted");
        ComboBox<SpeechSettings.Provider> provider = new ComboBox<>();
        provider.getItems().setAll(SpeechSettings.Provider.values());
        provider.setValue(settings.getProvider());
        provider.setConverter(new javafx.util.StringConverter<>() {
            public String toString(SpeechSettings.Provider p) { return p == SpeechSettings.Provider.DOUBAO ? "豆包 · 云端流式识别" : "SenseVoice · 本地识别"; }
            public SpeechSettings.Provider fromString(String s) { return null; }
        });
        provider.setMaxWidth(Double.MAX_VALUE);
        TextField appId = new TextField(settings.getAppId()); appId.setPromptText("火山引擎语音服务 APP ID");
        TextField resource = new TextField(settings.getResourceId()); resource.setPromptText("已开通的语音资源 ID");
        PasswordField accessKey = new PasswordField(); accessKey.setPromptText(settings.hasCredentials() ? "已安全保存；留空保持原密钥" : "Access Token / Access Key");
        Label cloudNote = new Label("只有选择豆包并启用语音后，音频才发送到火山引擎。API 费用由服务账号承担。密钥加密保存在本机，不写入配置仓库。");
        cloudNote.setWrapText(true); cloudNote.getStyleClass().add("shell-muted");
        Button save = new Button("保存语音设置"); save.getStyleClass().add("shell-primary");
        save.setOnAction(e -> {
            var chosen = provider.getValue();
            String id = appId.getText().trim(), rid = resource.getText().trim(), secret = accessKey.getText();
            accessKey.clear(); save.setDisable(true);
            worker.submit(() -> {
                try {
                    settings.setProvider(chosen); settings.setAppId(id); settings.setResourceId(rid);
                    if (!secret.isBlank()) settings.saveCredentials(secret);
                    settings.save(); manager.reload();
                    onFx(() -> { feedback.setText("已保存。回到语音页启用识别，即可开始使用。"); refresh.run(); });
                } catch (Exception failure) {
                    onFx(() -> feedback.setText("保存失败，请检查账号与系统密钥存储：" + failure.getClass().getSimpleName()));
                } finally { onFx(() -> save.setDisable(false)); }
            });
        });
        Button clear = new Button("清除保存的密钥");
        clear.setOnAction(e -> worker.submit(() -> {
            try { settings.clearCredentials(); onFx(() -> { feedback.setText("已清除云端密钥。"); accessKey.setPromptText("Access Token / Access Key"); }); }
            catch (Exception ex) { onFx(() -> feedback.setText("无法清除密钥，请稍后重试。")); }
        }));
        VBox providerCard = card("识别渠道", new Label("渠道"), provider, new Label("豆包 APP ID"), appId,
            new Label("语音资源 ID"), resource, new Label("访问密钥"), accessKey, cloudNote, new HBox(10, save, clear));

        updateModelStatus();
        progress.setMaxWidth(Double.MAX_VALUE); progress.setVisible(false); progress.setManaged(false);
        Button download = new Button("下载本地模型 · 约 239 MB"); download.getStyleClass().add("shell-primary");
        Button useExisting = new Button("导入已有模型文件夹");
        Label storage = new Label("存放在用户目录，升级应用时保留。下载经过固定版本与 SHA-256 校验。");
        storage.setWrapText(true); storage.getStyleClass().add("shell-muted");
        java.util.function.Consumer<java.nio.file.Path> runDownload = source -> {
            download.setDisable(true); useExisting.setDisable(true);
            progress.setVisible(true); progress.setManaged(true);
            feedback.setText(source == null ? "正在下载，可继续使用其他页面…" : "正在验证并导入模型…");
            worker.submit(() -> {
                try {
                    java.util.function.Consumer<Double> onProgress = value -> onFx(() -> progress.setProgress(value));
                    if (source == null) models.download(onProgress); else models.importFrom(source, onProgress);
                    manager.reload();
                    onFx(() -> { feedback.setText("模型已准备好。回到语音页启用本地识别。"); updateModelStatus(); refresh.run(); });
                } catch (Exception failure) {
                    onFx(() -> feedback.setText("模型操作未完成；已有模型保持可用。请检查网络和所选文件。"));
                } finally { onFx(() -> { download.setDisable(false); useExisting.setDisable(false); progress.setVisible(false); progress.setManaged(false); }); }
            });
        };
        download.setOnAction(e -> runDownload.accept(null));
        useExisting.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser(); chooser.setTitle("选择含 model.int8.onnx 和 tokens.txt 的文件夹");
            var dir = chooser.showDialog(getScene().getWindow()); if (dir != null) runDownload.accept(dir.toPath());
        });
        VBox modelCard = card("本地模型", modelStatus, storage, progress, new HBox(10, download, useExisting));
        feedback.setWrapText(true); feedback.getStyleClass().add("shell-muted");
        Label version = new Label("AhaKey Studio 1.1.0 · JavaFX 过渡客户端");
        version.getStyleClass().add("shell-muted");
        getChildren().addAll(title, lead, providerCard, modelCard, feedback, version);
    }
    private void updateModelStatus() { modelStatus.setText(models.isInstalled() ? "SenseVoice INT8 · 已下载" : "SenseVoice INT8 · 尚未下载权重"); }
    private static VBox card(String name, javafx.scene.Node... contents) {
        Label label = new Label(name); label.getStyleClass().add("card-title");
        VBox box = new VBox(10, label); box.getChildren().addAll(contents); box.getStyleClass().add("studio-card"); return box;
    }
    private void onFx(Runnable task) { Platform.runLater(() -> { if (!closed) task.run(); }); }
    public void close() { closed = true; worker.shutdownNow(); }
}
