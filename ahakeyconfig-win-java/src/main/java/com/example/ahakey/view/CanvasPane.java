package com.example.ahakey.view;

import com.example.ahakey.app.StudioController;
import com.example.ahakey.model.DeviceStatus;
import com.example.ahakey.model.ModeSlot;
import com.example.ahakey.model.StudioState;
import javafx.fxml.FXMLLoader;
import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.geometry.VPos;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.function.Consumer;

public class CanvasPane extends VBox {
    private final StudioState studioState;
    private final DeviceStatus deviceStatus;
    private final Consumer<ModeSlot> selectMode;

    private final Label modeGuidance = new Label();

    public CanvasPane(StudioController controller) {
        this(controller.getStudioState(), controller.getDeviceStatus(), controller::selectKeyboardMode);
    }

    CanvasPane(StudioState studioState, DeviceStatus deviceStatus, Consumer<ModeSlot> selectMode) {
        this.studioState = studioState;
        this.deviceStatus = deviceStatus;
        this.selectMode = selectMode;
        init();
    }

    private void init() {
        setSpacing(14);
        setPadding(new Insets(16));
        getStyleClass().add("canvas-pane");
        setMinWidth(0);
        setMaxWidth(Double.MAX_VALUE);
        setMaxHeight(USE_PREF_SIZE);

        getChildren().addAll(
            createHeader(),
            createPreviewCard()
        );

        studioState.selectedModeProperty().addListener((obs, oldValue, newValue) -> refreshPreview());
        refreshPreview();
    }

    private VBox createHeader() {
        VBox header = new VBox(8);
        header.getStyleClass().add("mode-header");

        Label keyboardMode = new Label("键盘模式");
        keyboardMode.getStyleClass().add("section-title");

        FlowPane picker = new FlowPane(6, 6);
        picker.setMinWidth(0);
        picker.getStyleClass().add("mode-picker");
        for (ModeSlot slot : ModeSlot.values()) {
            ToggleButton button = new ToggleButton(slot.getShortName());
            button.getStyleClass().add("mode-toggle");
            button.setUserData(slot);
            button.setMinWidth(USE_PREF_SIZE);
            button.prefWidthProperty().bind(Bindings.createDoubleBinding(() -> {
                double available = picker.getWidth() - picker.getInsets().getLeft() - picker.getInsets().getRight();
                int columns = available >= 660 ? 4 : 2;
                return Math.max(152, (available - picker.getHgap() * (columns - 1)) / columns);
            }, picker.widthProperty(), picker.insetsProperty()));
            button.setSelected(slot == studioState.getSelectedMode());
            button.setOnAction(event -> {
                for (var node : picker.getChildren()) {
                    if (node instanceof ToggleButton tb && tb.getUserData() instanceof ModeSlot s) {
                        tb.setSelected(s == slot);
                    }
                }
                selectMode.accept(slot);
            });
            picker.getChildren().add(button);
        }
        studioState.selectedModeProperty().addListener((obs, oldValue, newValue) -> {
            for (var node : picker.getChildren()) {
                if (node instanceof ToggleButton tb && tb.getUserData() instanceof ModeSlot slot) {
                    tb.setSelected(slot == newValue);
                }
            }
        });

        modeGuidance.getStyleClass().add("hero-subtitle");
        modeGuidance.setWrapText(true);
        modeGuidance.setMinWidth(0);
        header.getChildren().addAll(keyboardMode, picker, modeGuidance);
        return header;
    }

    private StackPane createPreviewCard() {
        StackPane preview = new StackPane();
        preview.getStyleClass().add("key-preview");

        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/CanvasLayout.fxml"));
            Region layout = loader.load();
            CanvasController controller = loader.getController();
            controller.setStudioState(studioState);
            controller.setDeviceStatus(deviceStatus);
            preview.getChildren().add(layout);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load keyboard preview", e);
        }

        return preview;
    }

    /** Uses logical pixels: JavaFX handles monitor scaling without scaling the controls twice. */
    public static GridPane createWorkspace(Region canvas, Region inspector) {
        GridPane workspace = new GridPane();
        workspace.getStyleClass().add("workspace");
        workspace.setMinWidth(0);
        workspace.setHgap(16);
        workspace.setVgap(16);
        workspace.add(canvas, 0, 0);
        workspace.add(inspector, 0, 1);
        GridPane.setValignment(canvas, VPos.TOP);
        GridPane.setValignment(inspector, VPos.TOP);
        GridPane.setHgrow(canvas, Priority.ALWAYS);
        GridPane.setHgrow(inspector, Priority.ALWAYS);
        Runnable reflow = () -> {
            boolean sideBySide = workspace.getWidth() >= 920;
            int count = sideBySide ? 2 : 1;
            if (workspace.getColumnConstraints().size() == count) return;
            workspace.getColumnConstraints().clear();
            for (int i = 0; i < count; i++) {
                ColumnConstraints column = new ColumnConstraints();
                column.setMinWidth(0);
                column.setPercentWidth(100.0 / count);
                column.setHgrow(Priority.ALWAYS);
                workspace.getColumnConstraints().add(column);
            }
            GridPane.setColumnIndex(inspector, sideBySide ? 1 : 0);
            GridPane.setRowIndex(inspector, sideBySide ? 0 : 1);
        };
        workspace.widthProperty().addListener((obs, before, after) -> reflow.run());
        reflow.run();
        return workspace;
    }

    private void refreshPreview() {
        ModeSlot mode = studioState.getSelectedMode();
        modeGuidance.setText(mode.getGuidance());
        modeGuidance.setVisible(mode.getGuidance() != null && !mode.getGuidance().isBlank());
        modeGuidance.setManaged(modeGuidance.isVisible());
    }
}
