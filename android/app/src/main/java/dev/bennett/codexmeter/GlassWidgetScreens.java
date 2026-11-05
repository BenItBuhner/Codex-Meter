package dev.bennett.codexmeter;

import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.RemoteViews;
import android.widget.Toast;
import dev.bennett.codexmeter.glass.GlassModel;
import dev.bennett.codexmeter.glass.GlassModel.Row;
import dev.bennett.codexmeter.glass.GlassModel.Section;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Widget settings in Liquid Glass: the home-screen widget (WidgetConfigActivity) and the
 * lock-screen widget (LockWidgetConfigActivity), with a live preview on top and Save in the
 * navigation bar. Back cancels, as in the One UI pages.
 */
final class GlassWidgetScreens {
    private GlassWidgetScreens() {}

    private static Row toggle(String key, String title, boolean on, GlassModel.OnToggle onToggle) {
        Row r = GlassScreens.row(Row.Kind.SWITCH, key, title, null);
        r.checked = on;
        r.onToggle = onToggle;
        return r;
    }

    private static Row providerRow(UsageProvider provider, boolean on, GlassModel.OnToggle onToggle) {
        Row r = toggle(provider.id, provider.displayName, on, onToggle);
        r.icon = "provider_" + provider.id;
        r.plainIcon = true;
        return r;
    }

    private static Row preview(byte[] png, double height) {
        Row r = GlassScreens.row(Row.Kind.IMAGE, "preview", "", null);
        r.image = png;
        r.imageHeight = height;
        return r;
    }

    // ------------------------------------------------------------------ lock screen

    static final class Lock extends GlassScreens.Screen {
        private final int appWidgetId;
        private final Runnable done;
        private int slots;
        private List<UsageProvider> providers;
        private final LinkedHashSet<String> meters = new LinkedHashSet<>();
        private boolean countdown;
        private boolean resetCredits;
        private boolean resetAction;
        private byte[] preview;

        Lock(GlassScreens screens, int appWidgetId, Runnable done) {
            super(screens);
            this.appWidgetId = appWidgetId;
            this.done = done;
        }

        @Override
        void opened(Intent intent) {
            Context c = activity();
            slots = SamsungLockWidgetSupport.slotCount(c, appWidgetId);
            providers = new ArrayList<>(ProviderPreferences.getLockWidgetProviders(c, appWidgetId, max()));
            LockWidgetOptions saved = AppPreferences.loadLockWidgetOptions(c, appWidgetId);
            meters.clear();
            for (String key : WidgetMeters.parse(saved.effectiveVisibleMeters())) {
                if (WidgetMeters.FIVE_HOUR.equals(key) || WidgetMeters.WEEKLY.equals(key)) {
                    meters.add(key);
                }
            }
            if (meters.isEmpty()) {
                meters.add(WidgetMeters.FIVE_HOUR);
            }
            countdown = saved.showCountdown;
            resetCredits = saved.showResetCredits;
            resetAction = saved.showResetAction;
            preview = null;
        }

        private int max() {
            return slots > 1 ? WidgetProviders.MAX_LOCK_WIDE : 1;
        }

        @Override
        GlassModel.Page page() {
            Context c = activity();
            if (providers == null) {
                opened(null);
            }
            GlassModel.Page page = new GlassModel.Page(GlassScreens.PREFIX + "lock_widget:" + appWidgetId,
                    "Lock-screen widget");
            page.actions.add(new GlassModel.Action("Save", null, true, this::save));
            if (preview == null) {
                preview = GlassScreens.png(SamsungLockWidgetSupport.previewBitmap(c, slots, providers, options()));
            }
            Section top = GlassScreens.section(null);
            top.rows.add(preview(preview, 150));
            page.sections.add(top);

            Section providerSection = GlassScreens.section(slots > 1 ? "Providers" : "Provider");
            providerSection.footer = slots > 1
                    ? "Pick one provider for two of its meters, or two providers for one meter each."
                    : "This widget shows one provider.";
            for (UsageProvider provider : ProviderPreferences.getOrder(c)) {
                providerSection.rows.add(providerRow(provider, providers.contains(provider),
                        on -> toggleProvider(provider, on)));
            }
            page.sections.add(providerSection);

            Section meterSection = GlassScreens.section("Meters");
            meterSection.footer = metersHint();
            UsageSnapshot snapshot = ProviderPreferences.loadSnapshot(c, providers.get(0));
            List<String> available = new ArrayList<>();
            for (String key : WidgetMeters.availableKeys(snapshot)) {
                if (WidgetMeters.FIVE_HOUR.equals(key) || WidgetMeters.WEEKLY.equals(key)) {
                    available.add(key);
                }
            }
            List<String> ordered = new ArrayList<>();
            for (String key : meters) {
                if (available.contains(key) && !ordered.contains(key)) {
                    ordered.add(key);
                }
            }
            for (String key : available) {
                if (!ordered.contains(key)) {
                    ordered.add(key);
                }
            }
            for (String key : ordered) {
                meterSection.rows.add(toggle(key, WidgetMeters.configLabel(key, snapshot), meters.contains(key),
                        on -> {
                            if (on) {
                                meters.add(key);
                            } else if (meters.size() > 1) {
                                meters.remove(key);
                            }
                            redraw();
                        }));
            }
            page.sections.add(meterSection);

            Section content = GlassScreens.section("Content");
            content.footer = "A reset is never consumed directly from the lock screen. The tile opens "
                    + "a confirmation screen first.";
            content.rows.add(toggle("countdown", "Show live time until reset", countdown, on -> {
                countdown = on;
                redraw();
            }));
            content.rows.add(toggle("credits", "Show reset-credit count", resetCredits, on -> {
                resetCredits = on;
                redraw();
            }));
            content.rows.add(toggle("action", "Tap tile to open Use reset confirmation", resetAction, on -> {
                resetAction = on;
                redraw();
            }));
            page.sections.add(content);
            return page;
        }

        private void redraw() {
            preview = null;
            changed();
        }

        private String metersHint() {
            int capacity = slots > 1 ? WidgetMeters.lockSlotCapacity() : 1;
            String message = providers.size() > 1
                    ? "With two providers, each shows the first meter it reports."
                    : slots > 1 ? "Lock widgets show up to " + capacity + " meters."
                    : "The gauge shows the first meter it can.";
            int selected = meters.size();
            if (selected > capacity && slots > 1 && providers.size() <= 1) {
                message += " " + (selected - capacity) + " extra selection"
                        + (selected - capacity == 1 ? " is" : "s are") + " ignored until you deselect others.";
            }
            return message;
        }

        private void toggleProvider(UsageProvider provider, boolean on) {
            List<UsageProvider> updated;
            if (slots <= 1 && on) {
                updated = new ArrayList<>();
                updated.add(provider);
            } else {
                updated = WidgetProviders.toggled(providers, provider, on,
                        ProviderPreferences.getOrder(activity()), max());
            }
            if (updated.contains(provider) != on && on) {
                Toast.makeText(activity(), "This widget shows up to " + max() + " providers.",
                        Toast.LENGTH_SHORT).show();
            }
            providers = updated;
            redraw();
        }

        private LockWidgetOptions options() {
            List<String> ordered = new ArrayList<>();
            for (String key : meters) {
                if (WidgetMeters.FIVE_HOUR.equals(key) || WidgetMeters.WEEKLY.equals(key)) {
                    ordered.add(key);
                }
            }
            boolean five = meters.contains(WidgetMeters.FIVE_HOUR);
            boolean weekly = meters.contains(WidgetMeters.WEEKLY);
            String metricMode = WidgetOptions.METRIC_BOTH;
            if (five && !weekly && meters.size() == 1) {
                metricMode = WidgetOptions.METRIC_FIVE_HOUR;
            } else if (weekly && !five && meters.size() == 1) {
                metricMode = WidgetOptions.METRIC_WEEKLY;
            }
            return new LockWidgetOptions(metricMode, resetCredits, resetAction, countdown,
                    WidgetMeters.serialize(ordered));
        }

        private void save() {
            Context c = activity();
            AppPreferences.saveLockWidgetOptions(c, appWidgetId, options());
            ProviderPreferences.setWidgetProviders(c, appWidgetId, providers);
            SamsungLockWidgetSupport.updateById(c, appWidgetId);
            activity().setResult(android.app.Activity.RESULT_OK,
                    new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId));
            Toast.makeText(c, "Lock-screen widget updated.", Toast.LENGTH_SHORT).show();
            providers = null;
            done.run();
        }
    }

    // ------------------------------------------------------------------ home screen

    static final class Home extends GlassScreens.Screen {
        private static final String[] TAP_LABELS = {"Open Codex Meter", "Refresh usage", "Use reset if available"};
        private static final String[] TAP_VALUES = {WidgetOptions.TAP_OPEN_APP, WidgetOptions.TAP_REFRESH,
                WidgetOptions.TAP_USE_RESET};
        private final int appWidgetId;
        private final Runnable done;
        private boolean loaded;
        private Bundle size = new Bundle();
        private List<UsageProvider> providers = new ArrayList<>();
        private final List<String> meterOrder = new ArrayList<>();
        private final LinkedHashSet<String> selected = new LinkedHashSet<>();
        private String tapAction = WidgetOptions.TAP_OPEN_APP;
        private boolean background;
        private int opacityIndex;
        private int style;
        private int theme;
        private int accent;
        private int display;
        private boolean percentSymbol;
        private UsageSnapshot snapshot;
        private byte[] preview;

        Home(GlassScreens screens, int appWidgetId, Runnable done) {
            super(screens);
            this.appWidgetId = appWidgetId;
            this.done = done;
        }

        @Override
        void opened(Intent intent) {
            Context c = activity();
            loaded = true;
            preview = null;
            readSize();
            WidgetOptions saved = AppPreferences.loadWidgetOptions(c, appWidgetId);
            tapAction = AppPreferences.getWidgetTapAction(c, appWidgetId);
            providers = new ArrayList<>(ProviderPreferences.getWidgetProviders(c, appWidgetId));
            snapshot = AppPreferences.loadSnapshot(c);
            background = saved.opacity > 0;
            opacityIndex = WidgetOptions.opacityIndex(saved.opacity);
            style = indexOf(WidgetOptionCatalog.STYLE_VALUES, saved.layoutPreference());
            theme = indexOf(WidgetOptionCatalog.THEME_VALUES, saved.theme);
            accent = indexOf(WidgetOptionCatalog.ACCENT_VALUES, saved.accent);
            display = indexOf(WidgetOptionCatalog.DISPLAY_VALUES, saved.displayMode);
            percentSymbol = saved.showPercentSymbol;
            List<String> available = WidgetMeters.availableKeys(snapshot);
            List<String> chosen = WidgetMeters.resolveVisibleForWidget(saved.effectiveVisibleMeters(), available,
                    saved.metricMode);
            meterOrder.clear();
            selected.clear();
            for (String key : chosen) {
                if (available.contains(key) && !meterOrder.contains(key)) {
                    meterOrder.add(key);
                    selected.add(key);
                }
            }
            for (String key : available) {
                if (!meterOrder.contains(key)) {
                    meterOrder.add(key);
                }
            }
            if (selected.isEmpty() && !meterOrder.isEmpty()) {
                selected.add(meterOrder.get(0));
            }
        }

        private void readSize() {
            try {
                Bundle options = AppWidgetManager.getInstance(activity()).getAppWidgetOptions(appWidgetId);
                if (options != null && !options.isEmpty()) {
                    size = new Bundle(options);
                }
            } catch (RuntimeException ignored) {
                // No size yet: the preview uses the default widget size.
            }
        }

        private static int indexOf(String[] values, String value) {
            for (int i = 0; i < values.length; i++) {
                if (values[i].equals(value)) {
                    return i;
                }
            }
            return 0;
        }

        @Override
        GlassModel.Page page() {
            Context c = activity();
            if (!loaded) {
                opened(null);
            }
            GlassModel.Page page = new GlassModel.Page(GlassScreens.PREFIX + "widget:" + appWidgetId,
                    "Customize widget");
            page.actions.add(new GlassModel.Action("Save", null, true, this::save));
            if (preview == null) {
                preview = renderPreview();
            }
            Section top = GlassScreens.section(null);
            top.rows.add(preview(preview, 200));
            page.sections.add(top);

            Section providerSection = GlassScreens.section("Providers");
            providerSection.footer = "Turn on more than one to stack them, one row per provider. Small sizes "
                    + "show the first one or two.";
            for (UsageProvider provider : ProviderPreferences.getOrder(c)) {
                providerSection.rows.add(providerRow(provider, providers.contains(provider), on -> {
                    List<UsageProvider> updated = WidgetProviders.toggled(providers, provider, on,
                            ProviderPreferences.getOrder(c), WidgetProviders.MAX_HOME);
                    if (updated.contains(provider) == on) {
                        providers = updated;
                    }
                    redraw();
                }));
            }
            page.sections.add(providerSection);

            Section appearance = GlassScreens.section("Appearance");
            appearance.rows.add(toggle("background", c.getString(R.string.widget_background), background, on -> {
                background = on;
                redraw();
            }));
            if (background) {
                Row opacity = GlassScreens.row(Row.Kind.SLIDER, "opacity", "Opacity", null);
                int steps = WidgetOptionCatalog.OPACITY_VALUES.length;
                opacity.steps = steps;
                opacity.fraction = steps <= 1 ? 0 : opacityIndex / (double) (steps - 1);
                opacity.onSlide = v -> {
                    int index = (int) Math.round(v * (steps - 1));
                    if (index != opacityIndex) {
                        opacityIndex = index;
                        redraw();
                    }
                };
                appearance.rows.add(opacity);
            }
            appearance.rows.add(choice("layout", "Layout", WidgetOptionCatalog.STYLE_LABELS, style, i -> style = i));
            appearance.rows.add(choice("theme", "Theme", WidgetOptionCatalog.THEME_LABELS, theme, i -> theme = i));
            appearance.rows.add(choice("accent", "Accent", WidgetOptionCatalog.ACCENT_LABELS, accent, i -> accent = i));
            page.sections.add(appearance);

            Section meters = GlassScreens.section("Meters");
            meters.footer = metersHint() + " Drag handles to choose which meters fill slots first. Empty "
                    + "selected meters keep a blank dash on the widget.";
            for (String key : meterOrder) {
                meters.rows.add(toggle(key, WidgetMeters.configLabel(key, snapshot), selected.contains(key), on -> {
                    if (on) {
                        selected.add(key);
                    } else if (selected.size() > 1) {
                        selected.remove(key);
                    }
                    redraw();
                }));
            }
            meters.onMove = (from, to) -> {
                meterOrder.add(to, meterOrder.remove(from));
                redraw();
            };
            page.sections.add(meters);

            Section content = GlassScreens.section("Content");
            content.rows.add(choice("display", "Percentage", WidgetOptionCatalog.DISPLAY_LABELS, display,
                    i -> display = i));
            content.rows.add(toggle("symbol", "Show % symbol", percentSymbol, on -> {
                percentSymbol = on;
                redraw();
            }));
            page.sections.add(content);

            Section tap = GlassScreens.section("Widget tap action");
            for (int i = 0; i < TAP_VALUES.length; i++) {
                String value = TAP_VALUES[i];
                Row r = GlassScreens.row(Row.Kind.RADIO, "tap" + i, TAP_LABELS[i], null);
                r.checked = value.equals(tapAction);
                r.onPress = () -> {
                    tapAction = value;
                    changed();
                };
                tap.rows.add(r);
            }
            page.sections.add(tap);
            return page;
        }

        private Row choice(String key, String title, String[] labels, int index, GlassModel.OnPick set) {
            Row r = GlassScreens.row(Row.Kind.CHOICE, key, title, null);
            r.entries = labels;
            r.selected = index;
            r.onChoose = i -> {
                set.on(i);
                redraw();
            };
            return r;
        }

        private void redraw() {
            preview = null;
            changed();
        }

        private WidgetOptions options() {
            int opacity = background ? WidgetOptionCatalog.OPACITY_VALUES[Math.max(0, Math.min(
                    WidgetOptionCatalog.OPACITY_VALUES.length - 1, opacityIndex))] : 0;
            List<String> ordered = new ArrayList<>();
            for (String key : meterOrder) {
                if (selected.contains(key)) {
                    ordered.add(key);
                }
            }
            return new WidgetOptions(WidgetOptionCatalog.STYLE_VALUES[style],
                    WidgetOptions.DENSITY_AUTO, WidgetOptions.SURFACE_ONE_UI, WidgetOptions.GRAPHIC_AUTO,
                    WidgetOptionCatalog.THEME_VALUES[theme], WidgetOptionCatalog.ACCENT_VALUES[accent], opacity,
                    WidgetOptions.RESET_HIDDEN, WidgetOptionCatalog.DISPLAY_VALUES[display],
                    WidgetOptions.METRIC_BOTH, false, false, false, false, false, false)
                    .withPercentSymbol(percentSymbol)
                    .withVisibleMeters(WidgetMeters.serialize(ordered));
        }

        private int option(String first, String second, int fallback) {
            int value = size.getInt(first, 0);
            if (value <= 0) {
                value = size.getInt(second, 0);
            }
            return value > 0 ? value : fallback;
        }

        private String metersHint() {
            WidgetOptions options = options();
            int height = option("appWidgetMinHeight", "appWidgetMaxHeight", 70);
            int width = option("appWidgetMinWidth", "appWidgetMaxWidth", 110);
            int rows = size.getInt("semAppWidgetRowSpan", 0);
            int columns = size.getInt("semAppWidgetColumnSpan", 0);
            String visual = WidgetMeters.resolveHomeVisualStyle(options.layoutPreference(), options.singleMetric(),
                    rows, columns, height, width);
            int capacity = WidgetMeters.slotCapacity(visual, height);
            String message = "This size shows up to " + capacity + " meter" + (capacity == 1 ? "" : "s") + ".";
            int count = selected.size();
            if (count > capacity) {
                message += " " + (count - capacity) + " extra selection" + (count - capacity == 1 ? " is" : "s are")
                        + " saved and appear when the widget is larger.";
            }
            return message;
        }

        private float aspect() {
            int w = option("appWidgetMinWidth", "appWidgetMaxWidth", 0);
            int h = option("appWidgetMinHeight", "appWidgetMaxHeight", 0);
            if (w > 0 && h > 0) {
                return Math.max(0.45f, Math.min(5.0f, w / (float) h));
            }
            int columns = size.getInt("semAppWidgetColumnSpan", 0);
            int rows = size.getInt("semAppWidgetRowSpan", 0);
            if (columns > 0 && rows > 0) {
                return columns / (float) rows;
            }
            return Math.max(0.45f, Math.min(4.0f, option("appWidgetMinWidth", "appWidgetMaxWidth", 220)
                    / (float) option("appWidgetMinHeight", "appWidgetMaxHeight", 70)));
        }

        /** The widget as the launcher would draw it, over the app's backdrop, as a PNG. */
        private byte[] renderPreview() {
            Context c = activity();
            float density = c.getResources().getDisplayMetrics().density;
            int boxW = Math.round(360 * density);
            int boxH = Math.round(200 * density);
            try {
                WidgetOptions options = options();
                RemoteViews remote = WidgetRenderer.buildPreview(c, appWidgetId, options, size, providers);
                FrameLayout surface = new FrameLayout(c);
                GradientDrawable bg = new GradientDrawable();
                boolean dark = WidgetOptions.THEME_DARK.equals(options.theme)
                        || (!WidgetOptions.THEME_LIGHT.equals(options.theme) && Ui.isDark(c));
                int alpha = Math.round(options.opacity * 2.55f);
                bg.setColor(dark ? Color.argb(alpha, 0, 0, 0) : Color.argb(alpha, 255, 255, 255));
                bg.setCornerRadius(28 * density);
                surface.setBackground(bg);
                View widget = remote.apply(c.getApplicationContext(), surface);
                surface.addView(widget, new FrameLayout.LayoutParams(-1, -1));
                int inset = Math.round(14 * density);
                int maxW = boxW - 2 * inset;
                int maxH = boxH - 2 * inset;
                int columns = size.getInt("semAppWidgetColumnSpan", 0);
                int w = columns > 0 ? Math.max(1, Math.round(maxW * (Math.min(4, columns) / 4.0f))) : maxW;
                int h = Math.max(1, Math.round(w / aspect()));
                if (h > maxH) {
                    h = maxH;
                    w = Math.max(1, Math.round(h * aspect()));
                }
                surface.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
                surface.layout(0, 0, w, h);
                Bitmap bitmap = Bitmap.createBitmap(boxW, boxH, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bitmap);
                Drawable backdrop = c.getDrawable(R.drawable.codex_meter_icon_bg);
                if (backdrop != null) {
                    // Center-crop the backdrop to the preview box.
                    int iw = Math.max(1, backdrop.getIntrinsicWidth());
                    int ih = Math.max(1, backdrop.getIntrinsicHeight());
                    float scale = Math.max(boxW / (float) iw, boxH / (float) ih);
                    int dw = Math.round(iw * scale);
                    int dh = Math.round(ih * scale);
                    backdrop.setBounds((boxW - dw) / 2, (boxH - dh) / 2, (boxW + dw) / 2, (boxH + dh) / 2);
                    backdrop.draw(canvas);
                }
                canvas.save();
                canvas.translate((boxW - w) / 2f, (boxH - h) / 2f);
                surface.draw(canvas);
                canvas.restore();
                return GlassScreens.png(bitmap);
            } catch (RuntimeException e) {
                Log.w("CodexMeterPreview", "Unable to render widget preview", e);
                return null;
            }
        }

        private void save() {
            Context c = activity();
            AppPreferences.saveWidgetOptions(c, appWidgetId, options());
            AppPreferences.saveWidgetTapAction(c, appWidgetId, tapAction);
            ProviderPreferences.setWidgetProviders(c, appWidgetId, providers);
            WidgetRenderer.update(c, AppWidgetManager.getInstance(c), appWidgetId);
            activity().setResult(android.app.Activity.RESULT_OK,
                    new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId));
            Toast.makeText(c, "Widget updated.", Toast.LENGTH_SHORT).show();
            loaded = false;
            done.run();
        }
    }
}
