package com.example.ahakey.view;
import javafx.geometry.Rectangle2D;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class CaptionPlacementTest {
    @Test void captionsUseTheRightMonitorAndStayAboveItsTaskbar() {
        var left = new Rectangle2D(0, 0, 1920, 1040);
        var right = new Rectangle2D(1920, -200, 2560, 1380);
        assertEquals(right, CaptionPlacement.choose(new Rectangle2D(2200, 30, 1200, 800), List.of(left, right)));
        var p = CaptionPlacement.place(right, 620, 104);
        assertEquals(2890, p.getMinX());
        assertTrue(p.getMaxY() < right.getMaxY());
    }
    @Test void negativeOriginsAndNarrowScreensStayInsideWorkArea() {
        var screen = new Rectangle2D(-1280, -100, 480, 600);
        var p = CaptionPlacement.place(screen, 620, 104);
        assertTrue(p.getMinX() >= screen.getMinX());
        assertTrue(p.getMaxX() <= screen.getMaxX());
        assertTrue(p.getMinY() >= screen.getMinY());
    }
}
