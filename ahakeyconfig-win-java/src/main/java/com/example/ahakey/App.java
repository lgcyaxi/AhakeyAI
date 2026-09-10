package com.example.ahakey;

import com.example.ahakey.app.StudioController;
import com.example.ahakey.platform.windows.WindowsVoiceRelayService;
import com.example.ahakey.platform.windows.WindowsVoiceTyping;
import com.example.ahakey.service.VoiceInputManager;
import com.example.ahakey.view.CanvasPane;
import com.example.ahakey.view.InspectorPane;
import com.example.ahakey.view.StatusBar;
import com.example.ahakey.view.TopBar;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.image.Image;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * AhaKey Studio 主应用类
 * 
 * JavaFX 桌面应用的入口，类比于 Java Web 中的 Servlet 或 Spring Boot 的 Application 类。
 * 
 * JavaFX 应用结构说明（类比 Web 开发）：
 * - Stage（舞台）：相当于浏览器窗口，是整个应用的顶层容器
 * - Scene（场景）：相当于 HTML 的 body，包含所有 UI 元素
 * - Pane（面板）：相当于 HTML 的 div，用于布局管理
 * - Node（节点）：相当于 HTML 的各种标签（button、label 等）
 * 
 * 生命周期方法：
 * 1. main() - 程序入口，调用 launch() 启动 JavaFX 运行时
 * 2. init() - 可选，在 start() 之前调用，用于初始化资源（本项目未使用）
 * 3. start() - 核心方法，构建 UI 界面
 * 4. stop() - 可选，应用关闭时调用（本项目通过 setOnCloseRequest 处理）
 */
public class App extends Application {
    
    private static final Logger logger = LoggerFactory.getLogger(App.class);
    
    /**
     * 主控制器，负责协调各个组件之间的通信
     * 类比于 Spring MVC 中的 Controller，管理应用的业务逻辑和状态
     */
    private StudioController controller;
    
    /**
     * 语音输入管理器，负责管理语音识别和键盘注入功能
     */
    private VoiceInputManager voiceInputManager;
    private TopBar topBar;
    private com.example.ahakey.view.StudioShell shell;
    private boolean shuttingDown;
    private FileChannel instanceChannel;
    private FileLock instanceLock;

    /**
     * JavaFX 应用的核心方法，负责初始化和显示主界面
     * @param primaryStage 主舞台（窗口），由 JavaFX 运行时自动创建
     */
    @Override
    public void start(Stage primaryStage) {
        if (!acquireInstanceLock()) {
            Platform.exit();
            return;
        }
        // 1. 创建主控制器，作为整个应用的核心协调者
        controller = new StudioController();
        
        // 2. 条件初始化语音输入管理器（根据 model.enabled 配置）
        initVoiceInputManager();
        
        // 2. 配置主窗口（Stage）属性
        primaryStage.setTitle("AhaKey Studio 1.1.1 · JavaFX");
        primaryStage.setMinWidth(800);              // 最小宽度（分屏/多屏适配）
        primaryStage.setMinHeight(600);              // 最小高度
        primaryStage.setWidth(1280);                 // 默认宽度
        primaryStage.setHeight(820);                 // 默认高度
        primaryStage.getIcons().add(new Image(getClass().getResourceAsStream("/ahakey.jpg")));

        // 3. 创建根布局容器
        // BorderPane 是一个边界布局，分为五个区域：top、bottom、left、right、center
        // 类比于 Web 中的 <header> + <footer> + <aside> + <main> 布局
        BorderPane root = new BorderPane();
        root.getStyleClass().add("root");  // 添加 CSS 样式类

        // 4. 创建各个 UI 组件
        
        // TopBar（顶部导航栏）：包含连接状态、模式选择、AhaType开关等
        topBar = new TopBar(
            controller, 
            controller.getDeviceStatus(), 
            controller.getStudioState(), 
            controller.getAgentManager()
        );
        
        // 设置语音输入管理器（仅当模型启用时）
        if (voiceInputManager != null) {
            topBar.setVoiceInputManager(voiceInputManager);
        }
        
        // Reflow by logical width; the device page owns vertical scrolling.
        CanvasPane canvasPane = new CanvasPane(controller);
        InspectorPane inspectorPane = new InspectorPane(controller);
        var deviceEditor = CanvasPane.createWorkspace(canvasPane, inspectorPane);
        
        // StatusBar（底部状态栏）：显示同步状态、连接状态等
        StatusBar statusBar = new StatusBar(
            controller.getDeviceStatus(), 
            controller.getStudioState()
        );

        // 5. 将组件组装到根布局中
        root.setTop(topBar);        // 顶部：导航栏
        shell = new com.example.ahakey.view.StudioShell(controller, topBar, deviceEditor, voiceInputManager);
        root.setCenter(shell);
        root.setBottom(statusBar);  // 底部：状态栏

        // 6. 创建场景（Scene）并设置样式
        // Scene 是所有 UI 元素的容器，必须绑定到 Stage 才能显示
        Scene scene = new Scene(root);
        // 加载 CSS 样式文件，类比于 Web 中的 <link rel="stylesheet">
        scene.getStylesheets().add(getClass().getResource("/style.css").toExternalForm());
        primaryStage.setScene(scene);

        // 7. 设置窗口显示相关的监听器
        // 监听窗口显示状态，在窗口首次显示时触发 CSS 样式应用和布局计算
        primaryStage.showingProperty().addListener((obs, old, showing) -> {
            if (showing) {
                Platform.runLater(() -> {
                    root.applyCss();
                    root.layout();
                });
            }
        });

        // JavaFX already lays out in logical pixels and refreshes output scale across monitors.
        // Do not restore a saved physical-DPI size over the user's current window geometry.

        // 8. 显示窗口
        primaryStage.show();
        com.example.ahakey.view.FloatingVoiceNotification.installScreenTracking();
        topBar.startBundledBleDriver();
        
        // 9. 设置窗口关闭时的清理逻辑
        // 类比于 Web 中的 beforeunload 事件
        primaryStage.setOnCloseRequest(event -> {
            shutdownApplication();
        });
    }

    @Override
    public void stop() {
        shutdownApplication();
    }

    private void shutdownApplication() {
        if (shuttingDown) {
            return;
        }
        shuttingDown = true;
        try {
            if (shell != null) shell.close();
            shutdownVoiceInputManager();
            if (controller != null) {
                controller.shutdown();
            }
        } catch (Exception e) {
            logger.warn("Application shutdown cleanup failed: {}", e.getMessage());
        } finally {
            try {
                if (topBar != null) {
                    topBar.shutdown();
                }
            } catch (Exception e) {
                logger.warn("Top bar shutdown cleanup failed: {}", e.getMessage());
            }
            Platform.exit();
            releaseInstanceLock();
            System.exit(0);
        }
    }
    
    /**
     * 初始化语音输入管理器
     */
    private void initVoiceInputManager() {
        logger.info("初始化语音输入管理器 (SenseVoice-Small)...");
        try {
            voiceInputManager = new VoiceInputManager();
            voiceInputManager.initialize();

            if (!voiceInputManager.isEnabled()) {
                logger.warn(
                    "本地语音服务不可用: {}",
                    voiceInputManager.getAvailabilityMessage()
                );
            }
            
            // 配置语音键回调：按下开始录音，释放停止录音
            if (WindowsVoiceTyping.isWindows()) {
                WindowsVoiceRelayService relay = WindowsVoiceRelayService.getInstance();
                relay.setOnVoiceKeyDown(() -> {
                    if (voiceInputManager != null && voiceInputManager.isActivated()) {
                        if (topBar != null) topBar.prepareCaptionForUtterance();
                        voiceInputManager.startRecording();
                    }
                });
                relay.setOnVoiceKeyUp(() -> {
                    if (voiceInputManager != null && voiceInputManager.isRecording()) {
                        voiceInputManager.stopRecording();
                    }
                });
                // 配置模拟录音回调（用于模拟按钮直接触发录音）
                relay.setOnSimulateRecordStart(() -> {
                    if (voiceInputManager != null && voiceInputManager.isActivated()) {
                        if (topBar != null) topBar.prepareCaptionForUtterance();
                        voiceInputManager.startRecording();
                    }
                });
                relay.setOnSimulateRecordStop(() -> {
                    if (voiceInputManager != null && voiceInputManager.isRecording()) {
                        voiceInputManager.stopRecording();
                    }
                });
                relay.setLocalVoiceRecordingSupplier(() ->
                    voiceInputManager != null && voiceInputManager.isRecording()
                );
            }
            
            logger.info("语音输入管理器初始化成功，等待用户点击“启动语音输入”");
        } catch (Exception e) {
            logger.error("语音输入管理器初始化失败: {}", e.getMessage());
            // 语音输入功能不可用，但不影响主应用运行
            voiceInputManager = null;
        }
    }
    
    /**
     * 关闭语音输入管理器
     */
    private void shutdownVoiceInputManager() {
        if (voiceInputManager != null) {
            logger.info("关闭语音输入管理器...");
            voiceInputManager.shutdown();
        }
    }
    
    /**
     * 获取语音输入管理器实例
     */
    public VoiceInputManager getVoiceInputManager() {
        return voiceInputManager;
    }

    /**
     * 程序入口方法
     * JavaFX 应用必须通过 launch() 方法启动，它会自动调用 init() 和 start()
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        launch(args);
    }
    
    private boolean acquireInstanceLock() {
        try {
            Path directory = Path.of(System.getProperty("user.home"), ".ahakey");
            Files.createDirectories(directory);
            instanceChannel = FileChannel.open(directory.resolve("studio-instance.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            try {
                instanceLock = instanceChannel.tryLock();
            } catch (OverlappingFileLockException alreadyLocked) {
                instanceLock = null;
            }
            if (instanceLock != null) return true;
            releaseInstanceLock();
            showStartupNotice("AhaKey Studio 已经在运行", "请使用已打开的窗口；如需切换版本，请先退出当前版本。");
        } catch (IOException error) {
            releaseInstanceLock();
            logger.warn("Cannot acquire application instance lock", error);
            showStartupNotice("无法启动 AhaKey Studio", "无法访问本机应用锁。请检查用户配置目录的访问权限后重试。");
        }
        return false;
    }

    private void releaseInstanceLock() {
        try {
            if (instanceLock != null) instanceLock.release();
            if (instanceChannel != null) instanceChannel.close();
        } catch (IOException error) {
            logger.warn("Cannot release application instance lock", error);
        } finally {
            instanceLock = null;
            instanceChannel = null;
        }
    }

    private static void showStartupNotice(String title, String detail) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("AhaKey Studio");
        alert.setHeaderText(title);
        alert.setContentText(detail);
        alert.showAndWait();
    }
}
