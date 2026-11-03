package dev.bennett.codexmeter.glass;

import static com.benitbuhner.glasse.kit.Kit.Box;
import static com.benitbuhner.glasse.kit.Kit.Icon;
import static com.benitbuhner.glasse.kit.KitUi.ListSection;

import com.benitbuhner.glasse.kit.Comp;
import com.benitbuhner.glasse.kit.El;
import com.benitbuhner.glasse.kit.Gesture;
import com.benitbuhner.glasse.kit.Hit;
import com.benitbuhner.glasse.kit.KTheme;
import com.benitbuhner.glasse.kit.Kit;
import com.benitbuhner.glasse.kit.P;
import com.benitbuhner.glasse.kit.R;
import com.benitbuhner.glasse.kit.State;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/**
 * A list section whose rows can be put in order by dragging their handles, like an iOS list in
 * edit mode: the rows make way under the finger and the drop reports the old and new positions.
 */
final class GlassReorder {
    private GlassReorder() {}

    /**
     * @param build makes row {@code i} with the given handle as its last trailing element
     * @param heights each row's height
     */
    static El section(String key, String header, String footer, int count, double[] heights,
            BiFunction<Integer, El, El> build, GlassModel.Mover onMove) {
        return R.h(Section, P.of("key", key, "header", header, "footer", footer, "count", count,
                "heights", heights, "build", build, "onMove", onMove));
    }

    private static final Comp Section = p -> {
        KTheme t = Kit.useTheme();
        int count = p.i("count");
        double[] heights = p.g("heights");
        BiFunction<Integer, El, El> build = p.g("build");
        GlassModel.Mover onMove = p.g("onMove");
        // {from, to} while a row is held
        State<int[]> drag = R.useState(null);
        int[] d = drag.get();
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            order.add(i);
        }
        if (d != null) {
            order.remove(Integer.valueOf(d[0]));
            order.add(Math.max(0, Math.min(count - 1, d[1])), d[0]);
        }
        List<El> rows = new ArrayList<>();
        for (int index : order) {
            El handle = Box(P.of("w", 40, "h", 44, "mr", -8, "align", "center", "justify", "center",
                            "noSwipe", true,
                            "gesture", new DragGesture(index, heights, drag, onMove)),
                    Icon(P.of("name", "menuLines", "size", 20, "color", t.pal.label3)));
            El row = build.apply(index, handle);
            rows.add(Box(P.of("key", "r" + index, "opacity", d != null && d[0] == index ? 0.6 : 1.0), row));
        }
        return ListSection(P.of("header", p.get("header"), "footer", p.get("footer")), rows);
    };

    private static final class DragGesture implements Gesture {
        private final int index;
        private final double[] heights;
        private final State<int[]> drag;
        private final GlassModel.Mover onMove;
        private double y0;
        private int target;

        DragGesture(int index, double[] heights, State<int[]> drag, GlassModel.Mover onMove) {
            this.index = index;
            this.heights = heights;
            this.drag = drag;
            this.onMove = onMove;
        }

        @Override
        public boolean down(double x, double y, Hit h) {
            y0 = y;
            target = index;
            drag.set(new int[] {index, index});
            return true;
        }

        @Override
        public void move(double x, double y) {
            double dy = y - y0;
            int to = index;
            if (dy > 0) {
                double past = 0;
                for (int i = index + 1; i < heights.length; i++) {
                    past += heights[i];
                    if (dy > past - heights[i] / 2) {
                        to = i;
                    }
                }
            } else {
                double past = 0;
                for (int i = index - 1; i >= 0; i--) {
                    past += heights[i];
                    if (-dy > past - heights[i] / 2) {
                        to = i;
                    }
                }
            }
            if (to != target) {
                target = to;
                drag.set(new int[] {index, to});
            }
        }

        @Override
        public void up(double x, double y, boolean cancel) {
            drag.set(null);
            if (!cancel && target != index && onMove != null) {
                onMove.move(index, target);
            }
        }

        @Override
        public boolean getNoSwipe() {
            return true;
        }
    }
}
