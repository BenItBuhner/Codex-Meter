package dev.bennett.codexmeter.glass;

import static com.benitbuhner.glasse.kit.Kit.Box;
import static com.benitbuhner.glasse.kit.Kit.Draw;
import static com.benitbuhner.glasse.kit.Kit.Icon;
import static com.benitbuhner.glasse.kit.Kit.Row;
import static com.benitbuhner.glasse.kit.Kit.Text;
import static com.benitbuhner.glasse.kit.KitUi.ListPad;
import static com.benitbuhner.glasse.kit.KitUi.ListRow;
import static com.benitbuhner.glasse.kit.KitUi.ListSection;

import com.benitbuhner.glasse.kit.Comp;
import com.benitbuhner.glasse.kit.El;
import com.benitbuhner.glasse.kit.Gesture;
import com.benitbuhner.glasse.kit.Hit;
import com.benitbuhner.glasse.kit.KTheme;
import com.benitbuhner.glasse.kit.Kit;
import com.benitbuhner.glasse.kit.NodeDraw;
import com.benitbuhner.glasse.kit.P;
import com.benitbuhner.glasse.kit.R;
import com.benitbuhner.glasse.kit.State;
import com.benitbuhner.glasse.port.Sk;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.skia.Canvas;
import org.jetbrains.skia.Paint;
import org.jetbrains.skia.PaintMode;
import org.jetbrains.skia.PaintStrokeCap;
import org.jetbrains.skia.PaintStrokeJoin;
import org.jetbrains.skia.Path;
import org.jetbrains.skia.PathEffect;

/**
 * A usage window's burn chart in a list card, like the One UI page's: this window's line in the
 * accent color, earlier windows faint on the same axis, the even-pace diagonal, the projection,
 * and a finger scrub that reads out the moment under it. Rows under it pick an earlier window.
 */
final class GlassChart {
    private GlassChart() {}

    private static final double H = 200;
    private static final double TOP = 34;
    private static final double BOTTOM = 24;
    private static final double SIDE = 16;

    static El card(GlassModel.Card c, String key) {
        return R.h(ChartCard, P.of("key", key, "c", c));
    }

    private static final Comp ChartCard = p -> {
        KTheme t = Kit.useTheme();
        GlassModel.Card c = p.g("c");
        GlassModel.Chart chart = c.chart;
        State<Integer> selected = R.useState(-1);
        State<Double> scrub = R.useState(-1.0);
        boolean dark = "dark".equals(t.scheme);
        int window = selected.get() != null ? selected.get() : -1;
        double at = scrub.get() != null ? scrub.get() : -1.0;
        String readout = null;
        double percent = -1;
        if (at >= 0 && chart.scrub != null) {
            readout = chart.scrub.at(window, at);
            percent = chart.scrub.percent(window, at);
        }
        double scrubPercent = percent;
        String accent = t.accent;
        String faintAccent = dark ? "rgba(10,132,255,0.55)" : "rgba(0,122,255,0.5)";
        String warn = dark ? "#ff9f0a" : "#ff9500";
        String grid = dark ? "rgba(128,128,128,0.2)" : "rgba(128,128,128,0.15)";
        String pace = dark ? "rgba(128,128,128,0.45)" : "rgba(128,128,128,0.37)";
        String old = dark ? "rgba(128,128,128,0.24)" : "rgba(128,128,128,0.19)";
        String card = t.pal.cell;

        El plot = Draw(P.of("h", H, "noSwipe", true,
                "gesture", new ScrubGesture(scrub),
                "draw", (NodeDraw) (cv, w, h, n, ax, ay) -> {
                    double left = SIDE;
                    double right = w - SIDE;
                    double top = TOP;
                    double bottom = h - BOTTOM;
                    Paint line = stroke(grid, 1);
                    cv.drawLine((float) left, (float) bottom, (float) right, (float) bottom, line);
                    cv.drawLine((float) left, (float) top, (float) right, (float) top, line);
                    if (chart.emptyText != null) {
                        return;
                    }
                    Paint diag = stroke(pace, 1.5);
                    diag.setPathEffect(PathEffect.Companion.makeDash(new float[] {6f, 5f}, 0f));
                    cv.drawLine((float) left, (float) bottom, (float) right, (float) top, diag);
                    for (int pass = 0; pass < 2; pass++) {
                        for (int i = 0; i < chart.previous.size(); i++) {
                            boolean on = i == window;
                            if ((pass == 1) != on) {
                                continue;
                            }
                            draw(cv, chart.previous.get(i), left, right, top, bottom,
                                    stroke(on ? faintAccent : old, on ? 2.5 : 1.5));
                        }
                    }
                    draw(cv, chart.current, left, right, top, bottom,
                            stroke(window >= 0 ? faintAccent : accent, 3));
                    if (chart.projection != null && window < 0) {
                        double[] pr = chart.projection;
                        Paint dash = stroke(chart.projectionWarning ? warn : faintAccent, 2);
                        dash.setPathEffect(PathEffect.Companion.makeDash(new float[] {7f, 5f}, 0f));
                        cv.drawLine((float) x(pr[0], left, right), (float) y(pr[1], top, bottom),
                                (float) x(pr[2], left, right), (float) y(pr[3], top, bottom), dash);
                    }
                    double sr = n.props.d("at", -1.0);
                    double sp = n.props.d("pct", -1.0);
                    if (sr >= 0 && sp >= 0) {
                        double sx = x(sr, left, right);
                        double sy = y(sp, top, bottom);
                        cv.drawLine((float) sx, (float) top, (float) sx, (float) bottom, stroke(faintAccent, 1.5));
                        cv.drawCircle((float) sx, (float) sy, 6f, Sk.paint(card));
                        cv.drawCircle((float) sx, (float) sy, 4f, Sk.paint(accent));
                    }
                },
                "at", at, "pct", scrubPercent));

        List<Object> kids = new ArrayList<>();
        kids.add(Box(P.of("h", H),
                plot,
                Row(P.of("abs", true, "left", SIDE, "right", SIDE, "top", 8, "pointerEvents", "none"),
                        Text(P.of("flex", 1, "size", 15, "weight", 600, "lines", 1, "color", t.pal.label),
                                chart.label),
                        Text(P.of("size", 12, "lines", 1, "color", t.pal.label2), chart.sampleLabel)),
                chart.emptyText != null
                        ? Text(P.of("abs", true, "left", SIDE, "right", SIDE, "top", TOP + 16, "size", 13,
                                "lines", 2, "color", t.pal.label2, "pointerEvents", "none"), chart.emptyText)
                        : null,
                Row(P.of("abs", true, "left", SIDE, "right", SIDE, "bottom", 4, "pointerEvents", "none"),
                        Text(P.of("flex", 1, "size", 11, "lines", 1, "color", t.pal.label2), "0%"),
                        Text(P.of("size", 11, "lines", 1, "color", t.pal.label2), "reset"))));
        kids.add(Text(P.of("size", 13, "lines", 1, "ml", SIDE, "mr", SIDE, "mt", 2, "mb", 10,
                "color", readout != null ? t.pal.label : t.pal.label2),
                readout != null ? readout : chart.hint == null ? "" : chart.hint));
        if (chart.legend) {
            // what each line is, in place of a paragraph explaining it
            kids.add(Row(P.of("ml", SIDE, "mr", SIDE, "gap", 18),
                    swatch("This window", accent, false, t),
                    swatch("Earlier windows", old, false, t)));
            kids.add(Row(P.of("ml", SIDE, "mr", SIDE, "mb", 8, "gap", 18),
                    swatch("Even pace", pace, true, t),
                    chart.projection != null
                            ? swatch("Projection", chart.projectionWarning ? warn : faintAccent, true, t) : null));
        }
        El top = ListPad(P.of("padX", 0, "padY", 8), kids);

        List<El> rows = new ArrayList<>();
        rows.add(top);
        for (int i = 0; i < chart.windows.size(); i++) {
            Object[] w = chart.windows.get(i);
            int index = (Integer) w[2];
            boolean on = index == window;
            rows.add(ListRow(P.of("key", "w" + i, "h", 60,
                    "label", Box(P.of("flex", 1, "gap", 2),
                            Text(P.of("size", 17, "ls", -0.34, "lines", 1, "color", t.pal.label), (String) w[0]),
                            Text(P.of("size", 13, "lines", 1, "color", t.pal.label2), (String) w[1])),
                    "onPress", (Runnable) () -> selected.set(on ? -1 : index)),
                    on ? Icon(P.of("name", "check", "size", 18, "color", t.accent)) : null));
        }
        return ListSection(P.of("header", c.title), rows);
    };

    /** A legend entry: a short line in the chart's color (dashed for guides) and its name. */
    private static El swatch(String label, String color, boolean dashed, KTheme t) {
        return Row(P.of("gap", 6, "align", "center", "h", 18),
                Draw(P.of("w", 16, "h", 4, "c", color, "dashed", dashed,
                        "draw", (NodeDraw) (cv, w, h, n, ax, ay) -> {
                            Paint paint = stroke(n.props.s("c"), 2.5);
                            if (n.props.b("dashed")) {
                                paint.setPathEffect(PathEffect.Companion.makeDash(new float[] {3f, 3f}, 0f));
                            }
                            cv.drawLine(1f, (float) (h / 2), (float) (w - 1), (float) (h / 2), paint);
                        })),
                Text(P.of("size", 12, "lines", 1, "color", t.pal.label2), label));
    }

    private static void draw(Canvas cv, double[] xy, double left, double right, double top, double bottom,
            Paint paint) {
        if (xy == null || xy.length < 4) {
            return;
        }
        StringBuilder d = new StringBuilder();
        for (int i = 0; i + 1 < xy.length; i += 2) {
            d.append(i == 0 ? 'M' : 'L').append(' ')
                    .append(x(xy[i], left, right)).append(' ')
                    .append(y(xy[i + 1], top, bottom)).append(' ');
        }
        Path path = Sk.INSTANCE.svg(d.toString());
        if (path != null) {
            cv.drawPath(path, paint);
        }
    }

    private static Paint stroke(String color, double width) {
        Paint p = Sk.paint(color);
        p.setMode(PaintMode.STROKE);
        p.setStrokeWidth((float) width);
        p.setStrokeCap(PaintStrokeCap.ROUND);
        p.setStrokeJoin(PaintStrokeJoin.ROUND);
        return p;
    }

    private static double x(double ratio, double left, double right) {
        return left + Math.max(0, Math.min(1, ratio)) * (right - left);
    }

    private static double y(double percent, double top, double bottom) {
        return bottom - Math.max(0, Math.min(100, percent)) / 100 * (bottom - top);
    }

    /** Drag across the chart to read out a moment; lifting the finger clears it. */
    private static final class ScrubGesture implements Gesture {
        private final State<Double> scrub;
        private double width;
        private double x0;
        private double lx0;

        ScrubGesture(State<Double> scrub) {
            this.scrub = scrub;
        }

        @Override
        public boolean down(double x, double y, Hit h) {
            width = h.n.w;
            x0 = x;
            lx0 = h.lx;
            set(x);
            return true;
        }

        @Override
        public void move(double x, double y) {
            set(x);
        }

        @Override
        public void up(double x, double y, boolean cancel) {
            scrub.set(-1.0);
        }

        @Override
        public boolean getNoSwipe() {
            return true;
        }

        private void set(double x) {
            double local = lx0 + (x - x0);
            scrub.set(Math.max(0, Math.min(1, (local - SIDE) / Math.max(1, width - 2 * SIDE))));
        }
    }
}
