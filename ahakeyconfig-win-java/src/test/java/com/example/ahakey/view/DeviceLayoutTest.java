package com.example.ahakey.view;

import com.example.ahakey.model.DeviceStatus;
import com.example.ahakey.model.IDEState;
import com.example.ahakey.model.LightEffectStyle;
import com.example.ahakey.model.ModeSlot;
import com.example.ahakey.model.StudioPart;
import com.example.ahakey.model.StudioState;
import com.example.ahakey.platform.VoiceRelayPlatform;
import com.example.ahakey.service.AgentManager;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Real JavaFX CSS/layout, with state-only views: no keyboard hooks, radio, account or app startup. */
@EnabledOnOs(OS.WINDOWS)
class DeviceLayoutTest {
    @BeforeAll
    static void startToolkit() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        try {
            Platform.startup(ready::countDown);
        } catch (IllegalStateException alreadyStarted) {
            ready.countDown();
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        Platform.setImplicitExit(false);
    }

    @Test
    void keyboardAndModeControlsStayInsideTheirCardsAcrossWindowWidths() throws Exception {
        onFxThread(() -> {
            StudioState state = new StudioState();
            CanvasPane canvas = new CanvasPane(state, new DeviceStatus(), state::setSelectedMode);
            VBox inspector = lightInspector();
            GridPane workspace = CanvasPane.createWorkspace(canvas, inspector);
            VBox page = new VBox(workspace);
            page.setPadding(new Insets(24));
            Region rail = new Region();
            rail.setMinWidth(168); rail.setPrefWidth(168); rail.setMaxWidth(168);
            BorderPane root = new BorderPane(page);
            root.setLeft(rail);
            Scene scene = new Scene(root, 1280, 2400);
            scene.getStylesheets().add(getClass().getResource("/style.css").toExternalForm());

            for (int width : new int[]{800, 1000, 1280, 1000, 800, 1280}) {
                root.resize(width, 2400);
                settle(root);
                assertEquals(width >= 1280 ? 2 : 1, workspace.getColumnConstraints().size());
                for (ModeSlot mode : ModeSlot.values()) {
                    state.setSelectedMode(mode);
                    settle(root);
                    List<Node> hotspots = new ArrayList<>();
                    for (String id : List.of("lightBarCard", "key1Card", "key2Card", "key3Card", "key4Card", "oledCard", "toggleCard", "modeBadge")) {
                        Node hotspot = canvas.lookup("#" + id);
                        assertNotNull(hotspot, id);
                        inside(hotspot, canvas.lookup(".key-preview"));
                        hotspots.add(hotspot);
                    }
                    noOverlap(hotspots);
                    List<Node> modes = new ArrayList<>(canvas.lookupAll(".mode-toggle"));
                    assertEquals(4, modes.size());
                    noOverlap(modes);
                    for (Node button : modes) {
                        inside(button, canvas.lookup(".mode-picker"));
                        assertTrue(((ToggleButton) button).getWidth() >= ((ToggleButton) button).prefWidth(-1) - 1,
                            "Profile label must remain whole at width " + width);
                    }
                    for (Node row : inspector.getChildren()) {
                        for (Node child : ((Parent) row).getChildrenUnmodifiable()) inside(child, row);
                        Parent controls = (Parent) ((Parent) row).getChildrenUnmodifiable().get(1);
                        noOverlap(controls.getChildrenUnmodifiable());
                        controls.getChildrenUnmodifiable().forEach(child -> inside(child, controls));
                    }
                }
            }
            if (Boolean.getBoolean("ahakey.layout.snapshots")) writeSnapshot(root);
        });
    }

    @Test
    void keySelectionChangesOnlyTheSelectedPartWithoutMovingHitTargets() throws Exception {
        onFxThread(() -> {
            StudioState state = new StudioState();
            CanvasPane canvas = new CanvasPane(state, new DeviceStatus(), state::setSelectedMode);
            new Scene(canvas, 450, 640).getStylesheets().add(getClass().getResource("/style.css").toExternalForm());
            canvas.resize(450, 640);
            settle(canvas);
            for (StudioPart part : List.of(StudioPart.LIGHT_BAR, StudioPart.KEY1, StudioPart.KEY2, StudioPart.KEY3, StudioPart.KEY4)) {
                String id = part == StudioPart.LIGHT_BAR ? "lightBarCard" : part.name().toLowerCase() + "Card";
                Node target = canvas.lookup("#" + id);
                Bounds before = geometry(target);
                target.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.SPACE, false, false, false, false));
                settle(canvas);
                assertEquals(part, state.getSelectedPart());
                assertEquals(before, geometry(target), "Selection must not resize " + id);
            }
        });
    }

    @Test
    void fullInspectorKeepsLightAndKeyControlsWithinTheNarrowColumn() throws Exception {
        onFxThread(() -> {
            StudioState state = new StudioState();
            VoiceRelayPlatform relay = new VoiceRelayPlatform();
            assertFalse(relay.listeningProperty().get());
            InspectorPane inspector = new InspectorPane(null, new DeviceStatus(), state, new AgentManager(), relay);
            new Scene(inspector, 452, 2800).getStylesheets().add(getClass().getResource("/style.css").toExternalForm());
            for (ModeSlot mode : ModeSlot.values()) {
                state.setSelectedMode(mode);
                for (StudioPart part : StudioPart.values()) {
                    state.setSelectedPart(part);
                    inspector.resize(452, 2800);
                    settle(inspector);
                    verifyLayoutChildren(inspector);
                }
            }
            assertFalse(relay.listeningProperty().get());
        });
    }

    private static void verifyLayoutChildren(Parent parent) {
        // Inspect authored containers, not skins (popup arrows and shadows have separate bounds).
        for (Node child : parent.getChildrenUnmodifiable()) {
            if (!child.isManaged() || !child.isVisible()) continue;
            inside(child, parent);
            if (child instanceof javafx.scene.layout.Pane pane) verifyLayoutChildren(pane);
        }
        if (parent instanceof javafx.scene.layout.HBox || parent instanceof VBox) {
            noOverlap(parent.getChildrenUnmodifiable().stream().filter(n -> n.isManaged() && n.isVisible()).toList());
        }
    }

    private static VBox lightInspector() {
        VBox inspector = new VBox(16);
        inspector.setMinWidth(0);
        inspector.setPadding(new Insets(24));
        for (IDEState state : IDEState.values()) {
            ComboBox<LightEffectStyle> combo = new ComboBox<>();
            combo.getItems().setAll(LightEffectStyle.values());
            combo.setValue(LightEffectStyle.values()[0]);
            inspector.getChildren().add(InspectorPane.createLightEffectRow(state, combo, new Button("测试")));
        }
        return inspector;
    }

    private static void settle(Parent root) {
        for (int i = 0; i < 4; i++) { root.applyCss(); root.layout(); }
    }

    private static Bounds geometry(Node node) { return node.localToScene(node.getLayoutBounds()); }

    private static void inside(Node child, Node container) {
        Bounds c = geometry(child), p = geometry(container);
        assertTrue(c.getMinX() >= p.getMinX() - 1 && c.getMaxX() <= p.getMaxX() + 1
            && c.getMinY() >= p.getMinY() - 1 && c.getMaxY() <= p.getMaxY() + 1,
            child.getId() + " " + c + " spills outside " + container.getId() + " " + p);
    }

    private static void noOverlap(List<? extends Node> nodes) {
        for (int i = 0; i < nodes.size(); i++) for (int j = i + 1; j < nodes.size(); j++) {
            Bounds a = geometry(nodes.get(i)), b = geometry(nodes.get(j));
            assertFalse(a.getMinX() < b.getMaxX() - 0.1 && a.getMaxX() > b.getMinX() + 0.1
                && a.getMinY() < b.getMaxY() - 0.1 && a.getMaxY() > b.getMinY() + 0.1,
                "Overlapping controls: " + nodes.get(i).getId() + " and " + nodes.get(j).getId());
        }
    }

    private static void onFxThread(Runnable action) throws Exception {
        FutureTask<Void> task = new FutureTask<>(action, null);
        Platform.runLater(task);
        task.get(30, TimeUnit.SECONDS);
    }

    private static void writeSnapshot(Parent root) {
        try {
            var image = root.snapshot(null, null);
            var output = new java.awt.image.BufferedImage((int) image.getWidth(), (int) image.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < output.getHeight(); y++) for (int x = 0; x < output.getWidth(); x++)
                output.setRGB(x, y, image.getPixelReader().getArgb(x, y));
            javax.imageio.ImageIO.write(output, "png", new java.io.File("target/device-layout.png"));
        } catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
    }
}
