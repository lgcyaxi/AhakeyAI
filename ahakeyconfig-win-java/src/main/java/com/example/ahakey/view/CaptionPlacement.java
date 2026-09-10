package com.example.ahakey.view;

import javafx.geometry.Rectangle2D;
import java.util.List;

/** Pure geometry shared by the subtitle overlay and its multi-monitor tests. */
public final class CaptionPlacement {
    private CaptionPlacement() {}
    public static Rectangle2D choose(Rectangle2D target, List<Rectangle2D> screens) {
        if (screens.isEmpty()) throw new IllegalArgumentException("No screens");
        Rectangle2D best = screens.get(0);
        double largest = -1;
        for (Rectangle2D screen : screens) {
            double w = Math.max(0, Math.min(target.getMaxX(), screen.getMaxX()) - Math.max(target.getMinX(), screen.getMinX()));
            double h = Math.max(0, Math.min(target.getMaxY(), screen.getMaxY()) - Math.max(target.getMinY(), screen.getMinY()));
            if (w * h > largest) { largest = w * h; best = screen; }
        }
        return best;
    }
    public static Rectangle2D place(Rectangle2D work, double preferredWidth, double height) {
        double width = Math.min(preferredWidth, Math.max(1, work.getWidth() - 32));
        double safeHeight = Math.min(height, Math.max(1, work.getHeight() - 32));
        return new Rectangle2D(work.getMinX() + (work.getWidth() - width) / 2,
            work.getMaxY() - safeHeight - 20, width, safeHeight);
    }
}
