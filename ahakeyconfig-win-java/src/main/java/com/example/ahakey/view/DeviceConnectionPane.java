package com.example.ahakey.view;

import com.example.ahakey.app.StudioController;
import com.example.ahakey.service.BleManager;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.util.Duration;

/** Device discovery is managed inside Studio, with no separate driver window. */
public final class DeviceConnectionPane extends VBox {
    private final Label status = new Label("设备后台模块随应用自动启动");
    private final ComboBox<BleManager.DiscoveredDevice> devices = new ComboBox<>();
    private final Timeline refresh;
    public DeviceConnectionPane(StudioController controller) {
        super(10); getStyleClass().add("studio-card");
        Label title = new Label("连接你的 AhaKey"); title.getStyleClass().add("card-title");
        devices.setPromptText("选择蓝牙设备"); devices.setMaxWidth(Double.MAX_VALUE); HBox.setHgrow(devices, Priority.ALWAYS);
        Button scan = new Button("刷新设备"); scan.getStyleClass().add("shell-secondary");
        scan.setOnAction(e -> { controller.getBleManager().rescanDevices(); controller.getBleManager().requestDevices(); });
        Button connect = new Button("连接"); connect.getStyleClass().add("shell-primary");
        connect.setOnAction(e -> { var selected = devices.getValue(); if (selected != null) controller.getBleManager().selectDevice(selected.id()); });
        Button disconnect = new Button("断开"); disconnect.getStyleClass().add("shell-secondary");
        disconnect.setOnAction(e -> controller.getBleManager().disconnectDevice());
        controller.getBleManager().setDeviceDiscoveryListener(snapshot -> Platform.runLater(() -> {
            var selected = devices.getValue(); devices.getItems().setAll(snapshot.devices());
            if (selected != null) devices.getItems().stream().filter(d -> d.id().equals(selected.id())).findFirst().ifPresent(devices::setValue);
            status.setText(switch (snapshot.state()) {
                case "ready" -> "设备连接及配置特征已就绪";
                case "connecting" -> "正在连接设备…";
                case "scanning" -> "正在发现附近的设备…";
                case "error" -> "连接未完成：" + snapshot.error();
                default -> "尚未连接，选择设备后点击连接";
            });
        }));
        status.setWrapText(true); status.getStyleClass().add("shell-muted");
        getChildren().addAll(title, status, new HBox(10, devices, scan, connect, disconnect));
        refresh = new Timeline(new KeyFrame(Duration.seconds(3), e -> controller.getBleManager().requestDevices()));
        refresh.setCycleCount(Timeline.INDEFINITE);
        sceneProperty().addListener((obs, old, scene) -> { if (scene == null) refresh.stop(); else { refresh.play(); controller.getBleManager().requestDevices(); } });
    }
}
