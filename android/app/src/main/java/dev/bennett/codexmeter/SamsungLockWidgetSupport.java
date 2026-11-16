package dev.bennett.codexmeter;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.util.SizeF;
import android.util.TypedValue;
import android.view.View;
import android.widget.RemoteViews;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/* JADX INFO: loaded from: classes.dex */
final class SamsungLockWidgetSupport {
    private static final ProviderSpec[] PROVIDERS = {
            new ProviderSpec(SamsungLockSquareWidget.class, Shape.SQUARE, Style.NUMBERS, Metric.BOTH),
            new ProviderSpec(SamsungLockWideWidget.class, Shape.WIDE, Style.NUMBERS, Metric.BOTH),
            new ProviderSpec(SamsungLockRingsSquareWidget.class, Shape.SQUARE, Style.RINGS, Metric.BOTH),
            new ProviderSpec(SamsungLockRingsWideWidget.class, Shape.WIDE, Style.RINGS, Metric.BOTH),
            new ProviderSpec(SamsungLockDialsSquareWidget.class, Shape.SQUARE, Style.DIALS, Metric.BOTH),
            new ProviderSpec(SamsungLockDialsWideWidget.class, Shape.WIDE, Style.DIALS, Metric.BOTH),
            new ProviderSpec(SamsungLockBarsSquareWidget.class, Shape.SQUARE, Style.BARS, Metric.BOTH),
            new ProviderSpec(SamsungLockBarsWideWidget.class, Shape.WIDE, Style.BARS, Metric.BOTH),
            new ProviderSpec(SamsungLockFiveHourWidget.class, Shape.SQUARE, Style.DIALS, Metric.FIVE_HOUR),
            new ProviderSpec(SamsungLockWeeklyWidget.class, Shape.SQUARE, Style.DIALS, Metric.WEEKLY)
    };

    enum Shape {
        WIDE,
        SQUARE
    }

    enum Style {
        NUMBERS,
        RINGS,
        DIALS,
        BARS
    }

    enum Metric {
        BOTH,
        FIVE_HOUR,
        WEEKLY
    }

    static final class LockInstance {
        final int appWidgetId;
        final Shape shape;
        final Style style;
        final Metric metric;

        LockInstance(int i, Shape shape, Style style, Metric metric) {
            this.appWidgetId = i;
            this.shape = shape;
            this.style = style;
            this.metric = metric;
        }

        String label() {
            if (this.metric == Metric.FIVE_HOUR) {
                return "Gauge";
            }
            if (this.metric == Metric.WEEKLY) {
                return "Weekly gauge (older)";
            }
            if (this.shape == Shape.WIDE && (this.style == Style.DIALS || this.style == Style.BARS)) {
                return "Gauges";
            }
            return SamsungLockWidgetSupport.styleLabel(this.style) + " · "
                    + (this.shape == Shape.SQUARE ? "Square" : "Wide") + " (older)";
        }
    }

    private static final class ProviderSpec {
        final Class<?> provider;
        final Shape shape;
        final Style style;
        final Metric metric;

        ProviderSpec(Class<?> cls, Shape shape, Style style, Metric metric) {
            this.provider = cls;
            this.shape = shape;
            this.style = style;
            this.metric = metric;
        }
    }

    private SamsungLockWidgetSupport() {
    }

    static void updateAll(Context context) {
        if (context != null) {
            Context contextApplication = application(context);
            AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(contextApplication);
            for (ProviderSpec providerSpec : PROVIDERS) {
                updateComponent(contextApplication, appWidgetManager, providerSpec.provider, providerSpec.shape, providerSpec.style, providerSpec.metric);
            }
        }
    }

    static void updateById(Context context, int i) {
        Context contextApplication;
        AppWidgetManager appWidgetManager;
        ProviderSpec providerSpecFindSpec;
        if (context != null && i != 0 && (providerSpecFindSpec = findSpec((appWidgetManager = AppWidgetManager.getInstance((contextApplication = application(context)))), contextApplication, i)) != null) {
            update(contextApplication, appWidgetManager, i, providerSpecFindSpec.shape, providerSpecFindSpec.style, providerSpecFindSpec.metric);
        }
    }

    static int countAll(Context context) {
        return placedWidgets(context).size();
    }

    static void enableAllProviders(Context context) {
        Context app = application(context);
        PackageManager packageManager = app.getPackageManager();
        for (ProviderSpec providerSpec : PROVIDERS) {
            try {
                packageManager.setComponentEnabledSetting(
                        new ComponentName(app, providerSpec.provider),
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        PackageManager.DONT_KILL_APP);
            } catch (RuntimeException exception) {
                Log.w("CodexMeterLock", "Unable to re-enable a lock widget provider", exception);
            }
        }
    }

    /** Placed widgets, asked of AppWidgetManager at most every few seconds (an IPC per provider). */
    private static volatile List<LockInstance> placedCache;
    private static volatile long placedAt;

    static List<LockInstance> placedWidgets(Context context) {
        long now = android.os.SystemClock.uptimeMillis();
        List<LockInstance> cached = placedCache;
        if (cached != null && now - placedAt < 5_000L) {
            return new ArrayList<>(cached);
        }
        List<LockInstance> fresh = placedWidgetsNow(context);
        placedCache = fresh;
        placedAt = now;
        return new ArrayList<>(fresh);
    }

    private static List<LockInstance> placedWidgetsNow(Context context) {
        List<LockInstance> placed = new ArrayList<>();
        if (context != null) {
            Context app = application(context);
            AppWidgetManager manager = AppWidgetManager.getInstance(app);
            for (ProviderSpec spec : PROVIDERS) {
                try {
                    for (int id : manager.getAppWidgetIds(new ComponentName(app, spec.provider))) {
                        placed.add(new LockInstance(id, spec.shape, spec.style, spec.metric));
                    }
                } catch (RuntimeException e) {
                    Log.w("CodexMeterLock", "Unable to enumerate lock widgets", e);
                }
            }
        }
        return placed;
    }

    static void updateIds(Context context, AppWidgetManager appWidgetManager, int[] iArr, Shape shape, Style style) {
        updateIds(context, appWidgetManager, iArr, shape, style, Metric.BOTH);
    }

    static void updateIds(Context context, AppWidgetManager appWidgetManager, int[] iArr, Shape shape, Style style, Metric metric) {
        if (context != null && appWidgetManager != null && iArr != null) {
            for (int i : iArr) {
                update(context, appWidgetManager, i, shape, style, metric);
            }
        }
    }

    static void update(Context context, AppWidgetManager appWidgetManager, int i, Shape shape, Style style) {
        update(context, appWidgetManager, i, shape, style, Metric.BOTH);
    }

    static void update(Context context, AppWidgetManager appWidgetManager, int i, Shape shape, Style style, Metric metric) {
        if (context != null && appWidgetManager != null && i != 0) {
            try {
                appWidgetManager.updateAppWidget(i, buildViews(context, appWidgetManager, i, shape, style, metric));
            } catch (RuntimeException e) {
                Log.w("CodexMeterLock", "Samsung lock widget update failed", e);
            }
        }
    }

    static RemoteViews buildViews(Context context, Shape shape, Style style) {
        return buildViews(context, null, 0, shape, style, Metric.BOTH);
    }

    static RemoteViews buildViews(Context context, AppWidgetManager appWidgetManager, int i, Shape shape, Style style) {
        return buildViews(context, appWidgetManager, i, shape, style, Metric.BOTH);
    }

    static RemoteViews buildViews(Context context, AppWidgetManager appWidgetManager, int i, Shape shape, Style style, Metric metric) {
        List<UsageProvider> providers = new ArrayList<>(ProviderPreferences.getLockWidgetProviders(
                context, i, metric == Metric.BOTH ? WidgetProviders.MAX_LOCK_WIDE : 1));
        return buildViews(context, appWidgetManager, i, shape, style, metric, providers,
                AppPreferences.loadLockWidgetOptions(context, i));
    }

    /** Which lock widget an id belongs to: one gauge (tiny) or two slots (wide and square). */
    static int slotCount(Context context, int appWidgetId) {
        Context app = application(context);
        ProviderSpec spec = findSpec(AppWidgetManager.getInstance(app), app, appWidgetId);
        return spec != null && spec.metric != Metric.BOTH ? 1 : 2;
    }

    /**
     * Lock widget for the given providers. One provider fills both slots with its meters; two
     * providers get one slot each, marked with the provider's icon or name.
     */
    static RemoteViews buildViews(Context context, AppWidgetManager appWidgetManager, int widgetId,
            Shape shape, Style style, Metric metric, List<UsageProvider> providers,
            LockWidgetOptions options) {
        RemoteViews views;
        int rootId;
        if (style == Style.BARS) {
            // The bar widgets are retired; tiles already placed draw dials instead.
            style = Style.DIALS;
        }
        UsageProvider provider = providers == null || providers.isEmpty()
                ? UsageProvider.CODEX : providers.get(0);
        boolean multi = metric == Metric.BOTH && providers != null && providers.size() > 1;
        boolean signedIn = SecureTokenStore.isSignedIn(context, provider);
        UsageSnapshot snapshot = signedIn
                ? ProviderPreferences.loadSnapshot(context, provider) : null;
        // Reset credits are a Codex feature.
        boolean codexOnly = provider == UsageProvider.CODEX && !multi;
        ResetCreditsSnapshot resetCredits = codexOnly
                ? AppPreferences.loadResetCredits(context) : null;
        if (!codexOnly) {
            LockWidgetOptions o = options;
            options = new LockWidgetOptions(o.metricMode, false,
                    false, o.showCountdown, o.visibleMeters);
        }
        int credits = resetCredits == null ? 0 : resetCredits.availableCount;
        LockMeterBinding binding;
        if (multi) {
            binding = bindProviders(context, providers, options);
            signedIn = binding.anySignedIn;
        } else {
            binding = markProvider(bindLockMeters(snapshot,
                    options), provider);
        }
        String name = multi ? "Usage" : provider.displayName;
        if (metric != Metric.BOTH) {
            List<String> keys = metric == Metric.WEEKLY
                    ? java.util.Collections.singletonList(WidgetMeters.WEEKLY)
                    : dialKeys(WidgetMeters.parse(
                                    options.effectiveVisibleMeters()),
                            snapshot);
            String key = keys.get(0);
            int inner = keys.size() > 1
                    ? remaining(WidgetMeters.meterWindow(keys.get(1), snapshot))
                    : -1;
            boolean fiveHour = WidgetMeters.FIVE_HOUR.equals(key);
            boolean monthlyFallback = !fiveHour
                    && snapshot != null
                    && snapshot.longWindowIsMonthly();
            int value = remaining(WidgetMeters.meterWindow(key, snapshot));
            int[] size = grantedSize(appWidgetManager, widgetId, Shape.SQUARE);
            // Codex keeps its meter icons; other providers show their mark so it's clear whose
            // usage the gauge shows.
            int icon = provider == UsageProvider.CODEX
                    ? (fiveHour ? R.drawable.ic_oui_time : R.drawable.ic_oui_calendar_week)
                    : ProvidersActivity.iconRes(provider);
            RemoteViews single = new RemoteViews(context.getPackageName(), R.layout.widget_lock_dial_single);
            single.setImageViewBitmap(R.id.lock_graphic_image,
                    SamsungLockGraphics.renderSingle(context, value, inner, signedIn,
                            size[0], size[1], icon));
            String metricName = fiveHour ? "five hour"
                    : monthlyFallback ? "monthly" : "weekly";
            single.setContentDescription(R.id.lock_graphic_root, signedIn
                    ? name + " " + metricName + " " + value(value) + " remaining"
                            + (keys.size() > 1 ? ", five hour " + value(inner) + " remaining" : "")
                    : name + ", sign in required");
            applyOpenIntent(context, single, R.id.lock_graphic_root, widgetId, shape, style,
                    options, signedIn, credits, provider);
            return single;
        }
        if (style == Style.NUMBERS) {
            views = buildNumberViews(context, shape, signedIn, binding,
                    options, credits);
            rootId = shape == Shape.SQUARE ? R.id.lock_square_root : R.id.lock_wide_root;
        } else if (style == Style.BARS) {
            views = buildNativeBarViews(context, shape, signedIn, binding,
                    options, credits);
            rootId = R.id.lock_graphic_root;
        } else {
            views = buildArcViews(context, appWidgetManager, widgetId, shape, style,
                    signedIn, binding, options, credits);
            rootId = R.id.lock_graphic_root;
        }
        applyCountdowns(views, shape, style, options,
                binding);
        views.setContentDescription(rootId, contentDescription(name, signedIn,
                binding, style, options, credits));
        applyOpenIntent(context, views, rootId, widgetId, shape, style,
                options, signedIn, credits, provider);
        return views;
    }

    /**
     * Configuration preview: the gauges a lock widget would draw for these providers and
     * options, with sample values where there is no data yet.
     */
    static android.graphics.Bitmap previewBitmap(Context context, int slots,
            List<UsageProvider> providers, LockWidgetOptions options) {
        UsageProvider provider = providers == null || providers.isEmpty()
                ? UsageProvider.CODEX : providers.get(0);
        UsageSnapshot snapshot = ProviderPreferences.loadSnapshot(context, provider);
        if (slots <= 1) {
            List<String> keys = dialKeys(WidgetMeters.parse(options.effectiveVisibleMeters()),
                    snapshot);
            String key = keys.get(0);
            int value = remaining(WidgetMeters.meterWindow(key, snapshot));
            int inner = keys.size() > 1
                    ? remaining(WidgetMeters.meterWindow(keys.get(1), snapshot)) : -1;
            int icon = provider == UsageProvider.CODEX
                    ? (WidgetMeters.FIVE_HOUR.equals(key)
                            ? R.drawable.ic_oui_time : R.drawable.ic_oui_calendar_week)
                    : ProvidersActivity.iconRes(provider);
            return SamsungLockGraphics.renderSingle(context, value < 0 ? 73 : value,
                    keys.size() > 1 ? (inner < 0 ? 44 : inner) : -1, true, 82, 82, icon);
        }
        LockMeterBinding binding = providers != null && providers.size() > 1
                ? bindProviders(context, providers, options)
                : markProvider(bindLockMeters(snapshot, options), provider);
        int primary = binding.primaryRemaining < 0 ? 73 : binding.primaryRemaining;
        int secondary = !binding.showSecondary ? -1
                : binding.secondaryRemaining < 0 ? 44 : binding.secondaryRemaining;
        int primaryInner = binding.primary == null || !binding.primary.nested() ? -1
                : binding.primary.inner < 0 ? 44 : binding.primary.inner;
        int secondaryInner = binding.secondary == null || !binding.secondary.nested() ? -1
                : binding.secondary.inner < 0 ? 44 : binding.secondary.inner;
        return SamsungLockGraphics.render(context, Shape.WIDE, Style.DIALS, primary,
                primaryInner, secondary, secondaryInner, true, 180, 82, options, 0,
                binding.primary == null ? R.drawable.ic_oui_time : binding.primary.iconRes,
                binding.secondary == null ? 0 : binding.secondary.iconRes);
    }

    private static LockMeterBinding bindLockMeters(UsageSnapshot snapshot, LockWidgetOptions options) {
        List<String> available = WidgetMeters.availableKeys(snapshot);
        String metricMode = options == null ? WidgetOptions.METRIC_BOTH : options.metricMode;
        List<String> resolved = WidgetMeters.resolveVisibleForWidget(
                options == null ? "" : options.effectiveVisibleMeters(), available, metricMode);
        ArrayList<String> usageKeys = new ArrayList<>();
        for (String key : resolved) {
            if (WidgetMeters.FIVE_HOUR.equals(key) || WidgetMeters.WEEKLY.equals(key)
                    || WidgetMeters.isLimitKey(key)) {
                usageKeys.add(key);
            }
        }
        if (snapshot != null) {
            // Drop meters this provider doesn't report (Cursor has no five-hour window), so a
            // dial never sits at "—" next to real data.
            ArrayList<String> reported = new ArrayList<>();
            for (String key : usageKeys) {
                UsageWindow window = WidgetMeters.isLimitKey(key) ? null
                        : WidgetMeters.meterWindow(key, snapshot);
                if (window != null || WidgetMeters.isLimitKey(key)) reported.add(key);
            }
            if (reported.isEmpty()) {
                reported.addAll(WidgetProviders.metersFor(usageKeys,
                        snapshot.fiveHour != null, snapshot.longWindow() != null, 1));
            }
            usageKeys = reported;
        }
        usageKeys = weeklyFirst(usageKeys);
        usageKeys = new ArrayList<>(WidgetMeters.cap(usageKeys, WidgetMeters.lockSlotCapacity()));
        if (usageKeys.isEmpty()) {
            usageKeys.add(WidgetMeters.FIVE_HOUR);
        }
        LockMeterSlot primary = lockSlot(usageKeys.get(0), snapshot);
        LockMeterSlot secondary = usageKeys.size() > 1 ? lockSlot(usageKeys.get(1), snapshot) : null;
        return new LockMeterBinding(primary, secondary);
    }

    /**
     * One slot per provider, labeled with its mark. The dial leads with the weekly window; when
     * the five-hour window is visible too, it rides inside the same dial as a lighter ring.
     */
    private static LockMeterBinding bindProviders(Context context, List<UsageProvider> providers,
            LockWidgetOptions options) {
        List<String> visible = WidgetMeters.parse(options == null ? ""
                : options.effectiveVisibleMeters());
        List<LockMeterSlot> slots = new ArrayList<>();
        boolean anySignedIn = false;
        for (UsageProvider provider : providers) {
            if (slots.size() >= WidgetProviders.MAX_LOCK_WIDE) break;
            boolean signedIn = SecureTokenStore.isSignedIn(context, provider);
            anySignedIn |= signedIn;
            UsageSnapshot snapshot = signedIn
                    ? ProviderPreferences.loadSnapshot(context, provider) : null;
            List<String> keys = dialKeys(visible, snapshot);
            String key = keys.get(0);
            UsageWindow window = WidgetMeters.meterWindow(key, snapshot);
            UsageWindow innerWindow = keys.size() > 1
                    ? WidgetMeters.meterWindow(keys.get(1), snapshot) : null;
            slots.add(new LockMeterSlot(key, WidgetProviders.shortTag(provider),
                    remaining(window), window, ProvidersActivity.iconRes(provider),
                    keys.size() > 1 ? remaining(innerWindow) : -1, keys.size() > 1));
        }
        LockMeterBinding binding = new LockMeterBinding(slots.get(0),
                slots.size() > 1 ? slots.get(1) : null);
        binding.anySignedIn = anySignedIn;
        return binding;
    }

    /**
     * One provider other than Codex: its mark replaces the first dial's meter icon, so the widget
     * says whose usage it shows. Codex keeps its clock and calendar icons.
     */
    private static LockMeterBinding markProvider(LockMeterBinding binding,
            UsageProvider provider) {
        if (provider == null || provider == UsageProvider.CODEX || binding.primary == null) {
            return binding;
        }
        LockMeterSlot p = binding.primary;
        LockMeterBinding marked = new LockMeterBinding(new LockMeterSlot(p.key, p.label,
                p.remaining, p.window, ProvidersActivity.iconRes(provider), p.inner,
                p.nested()), binding.secondary);
        marked.anySignedIn = binding.anySignedIn;
        return marked;
    }

    private static LockMeterSlot lockSlot(String key, UsageSnapshot snapshot) {
        if (WidgetMeters.FIVE_HOUR.equals(key)) {
            return new LockMeterSlot(key, "5H",
                    remaining(snapshot == null ? null : snapshot.fiveHour),
                    snapshot == null ? null : snapshot.fiveHour,
                    R.drawable.ic_oui_time);
        }
        if (WidgetMeters.WEEKLY.equals(key)) {
            UsageWindow longWindow = WidgetMeters.meterWindow(key, snapshot);
            return new LockMeterSlot(key,
                    WidgetMeters.weeklyMeterIsMonthly(snapshot) ? "MO" : "W",
                    remaining(longWindow), longWindow,
                    R.drawable.ic_oui_calendar_week);
        }
        UsageLimit limit = WidgetMeters.findLimit(key, snapshot);
        UsageWindow window = limit == null ? null
                : (WidgetMeters.isLimitPrimary(key) ? limit.primary : limit.secondary);
        String label = WidgetMeters.shortLabel(key, snapshot);
        if (label.length() > 6) {
            label = label.substring(0, 6);
        }
        return new LockMeterSlot(key, label.toUpperCase(Locale.ROOT), remaining(window), window,
                WidgetMeters.isLimitPrimary(key)
                        ? R.drawable.ic_oui_time : R.drawable.ic_oui_calendar_week);
    }

    /**
     * The usage keys one dial shows, main window first: weekly (or the monthly long window)
     * leads, and the five-hour window follows as the dial's lighter inner ring when it is
     * visible and reported. Providers without one of the windows get a single key.
     */
    static List<String> dialKeys(List<String> visible, UsageSnapshot snapshot) {
        // No data yet keeps the full dial shape so it doesn't jump once data lands.
        boolean hasFiveHour = snapshot == null || snapshot.fiveHour != null;
        boolean hasLongWindow = snapshot == null || snapshot.longWindow() != null;
        return weeklyFirst(WidgetProviders.metersFor(visible, hasFiveHour, hasLongWindow, 2));
    }

    /** Moves the weekly meter ahead of the five-hour meter; other keys keep their order. */
    static ArrayList<String> weeklyFirst(List<String> keys) {
        ArrayList<String> ordered = new ArrayList<>(keys);
        int fiveHour = ordered.indexOf(WidgetMeters.FIVE_HOUR);
        int weekly = ordered.indexOf(WidgetMeters.WEEKLY);
        if (fiveHour >= 0 && weekly > fiveHour) {
            ordered.set(fiveHour, WidgetMeters.WEEKLY);
            ordered.set(weekly, WidgetMeters.FIVE_HOUR);
        }
        return ordered;
    }

    private static final class LockMeterSlot {
        final String key;
        final String label;
        final int remaining;
        final UsageWindow window;
        final int iconRes;
        /** Five-hour percent left for the lighter inner ring, -1 when unknown. */
        final int inner;
        private final boolean nested;

        LockMeterSlot(String key, String label, int remaining, UsageWindow window, int iconRes) {
            this(key, label, remaining, window, iconRes, -1, false);
        }

        LockMeterSlot(String key, String label, int remaining, UsageWindow window, int iconRes,
                int inner, boolean nested) {
            this.key = key;
            this.label = label;
            this.remaining = remaining;
            this.window = window;
            this.iconRes = iconRes;
            this.inner = inner;
            this.nested = nested;
        }

        boolean nested() {
            return nested;
        }
    }

    private static final class LockMeterBinding {
        final LockMeterSlot primary;
        final LockMeterSlot secondary;
        final int primaryRemaining;
        final int secondaryRemaining;
        final boolean showPrimary;
        final boolean showSecondary;
        /** Multi-provider widgets show slots while at least one provider is signed in. */
        boolean anySignedIn;

        LockMeterBinding(LockMeterSlot primary, LockMeterSlot secondary) {
            this.primary = primary;
            this.secondary = secondary;
            this.showPrimary = primary != null;
            this.showSecondary = secondary != null;
            this.primaryRemaining = primary == null ? -1 : primary.remaining;
            this.secondaryRemaining = secondary == null ? -1 : secondary.remaining;
        }

        boolean singleMetric() {
            return showPrimary && !showSecondary;
        }
    }

    private static RemoteViews buildNumberViews(Context context, Shape shape, boolean signedIn,
            LockMeterBinding binding, LockWidgetOptions lockWidgetOptions, int credits) {
        int layout = shape == Shape.SQUARE ? R.layout.widget_lock_square : R.layout.widget_lock_wide;
        int valueId = shape == Shape.SQUARE ? R.id.lock_square_value : R.id.lock_wide_value;
        RemoteViews remoteViews = new RemoteViews(context.getPackageName(), layout);
        boolean showCredits = lockWidgetOptions.showResetCredits || lockWidgetOptions.showResetAction;
        remoteViews.setTextViewText(valueId, numberText(signedIn, binding, shape, lockWidgetOptions, credits));
        remoteViews.setTextViewTextSize(valueId, TypedValue.COMPLEX_UNIT_SP, numberTextSize(shape, binding.singleMetric(), showCredits));
        return remoteViews;
    }

    private static RemoteViews buildNativeBarViews(Context context, Shape shape, boolean signedIn,
            LockMeterBinding binding, LockWidgetOptions lockWidgetOptions, int credits) {
        float valueSize;
        float labelSize;
        RemoteViews remoteViews = new RemoteViews(context.getPackageName(), shape == Shape.SQUARE ? R.layout.widget_lock_bars_square : R.layout.widget_lock_bars_wide);
        boolean showCredits = lockWidgetOptions.showResetCredits || lockWidgetOptions.showResetAction;
        if (!signedIn) {
            remoteViews.setViewVisibility(R.id.lock_bar_primary_group, View.VISIBLE);
            remoteViews.setViewVisibility(R.id.lock_bar_secondary_group, View.GONE);
            remoteViews.setTextViewText(R.id.lock_bar_primary_label, "");
            remoteViews.setTextViewText(R.id.lock_bar_primary_value, "SIGN IN");
            remoteViews.setTextViewTextSize(R.id.lock_bar_primary_value, TypedValue.COMPLEX_UNIT_SP, shape == Shape.SQUARE ? 10.0f : 12.0f);
            remoteViews.setViewVisibility(R.id.lock_bar_primary_progress, View.GONE);
            return remoteViews;
        }
        boolean showPrimary = binding.showPrimary;
        boolean showSecondary = binding.showSecondary;
        remoteViews.setViewVisibility(R.id.lock_bar_primary_group, showPrimary ? View.VISIBLE : View.GONE);
        remoteViews.setViewVisibility(R.id.lock_bar_secondary_group, showSecondary ? View.VISIBLE : View.GONE);
        remoteViews.setViewVisibility(R.id.lock_bar_primary_progress, showPrimary ? View.VISIBLE : View.GONE);
        remoteViews.setViewVisibility(R.id.lock_bar_secondary_progress, showSecondary ? View.VISIBLE : View.GONE);
        boolean primaryCredits = showCredits && showPrimary;
        boolean secondaryCredits = showCredits && !showPrimary && showSecondary;
        String primaryLabel = binding.primary == null ? "5H" : binding.primary.label;
        String secondaryLabel = binding.secondary == null ? "W" : binding.secondary.label;
        remoteViews.setTextViewText(R.id.lock_bar_primary_label, labelWithReset(primaryLabel, primaryCredits, credits));
        remoteViews.setTextViewText(R.id.lock_bar_secondary_label, labelWithReset(secondaryLabel, secondaryCredits, credits));
        remoteViews.setTextViewText(R.id.lock_bar_primary_value, compactValue(binding.primaryRemaining));
        remoteViews.setTextViewText(R.id.lock_bar_secondary_value, compactValue(binding.secondaryRemaining));
        remoteViews.setProgressBar(R.id.lock_bar_primary_progress, 100, progress(binding.primaryRemaining), false);
        remoteViews.setProgressBar(R.id.lock_bar_secondary_progress, 100, progress(binding.secondaryRemaining), false);
        if (binding.singleMetric()) {
            valueSize = shape == Shape.SQUARE ? 15.0f : 17.0f;
        } else {
            valueSize = shape == Shape.SQUARE ? 10.0f : 11.0f;
        }
        if (binding.singleMetric()) {
            labelSize = shape == Shape.SQUARE ? 9.0f : 10.0f;
        } else {
            labelSize = shape == Shape.SQUARE ? 7.5f : 8.0f;
        }
        remoteViews.setTextViewTextSize(R.id.lock_bar_primary_value, TypedValue.COMPLEX_UNIT_SP, valueSize);
        remoteViews.setTextViewTextSize(R.id.lock_bar_secondary_value, TypedValue.COMPLEX_UNIT_SP, valueSize);
        remoteViews.setTextViewTextSize(R.id.lock_bar_primary_label, TypedValue.COMPLEX_UNIT_SP, labelSize);
        remoteViews.setTextViewTextSize(R.id.lock_bar_secondary_label, TypedValue.COMPLEX_UNIT_SP, labelSize);
        return remoteViews;
    }

    private static RemoteViews buildArcViews(Context context, AppWidgetManager appWidgetManager, int widgetId, Shape shape, Style style, boolean signedIn, LockMeterBinding binding, LockWidgetOptions lockWidgetOptions, int credits) {
        String centerText;
        RemoteViews remoteViews = new RemoteViews(context.getPackageName(), graphicLayout(shape, style));
        int primaryRemaining = binding.primaryRemaining;
        int secondaryRemaining = binding.secondaryRemaining;
        int primaryIconRes = binding.primary == null
                ? R.drawable.ic_oui_time : binding.primary.iconRes;
        // 0 tells the renderer there is no second meter, so it centers one dial.
        int secondaryIconRes = binding.secondary == null ? 0 : binding.secondary.iconRes;
        int primaryInner = binding.primary != null && binding.primary.nested()
                ? binding.primary.inner : -1;
        int secondaryInner = binding.secondary != null && binding.secondary.nested()
                ? binding.secondary.inner : -1;
        if (shape == Shape.WIDE) {
            int[] size = grantedSize(appWidgetManager, widgetId, shape);
            remoteViews.setImageViewBitmap(R.id.lock_graphic_image,
                    SamsungLockGraphics.render(context, shape, style, primaryRemaining, primaryInner, secondaryRemaining,
                            secondaryInner, signedIn, size[0], size[1], lockWidgetOptions, credits,
                            primaryIconRes, secondaryIconRes));
            if (!signedIn) {
                remoteViews.setViewVisibility(R.id.lock_graphic_primary_group, View.VISIBLE);
                remoteViews.setViewVisibility(R.id.lock_graphic_secondary_group, View.GONE);
                remoteViews.setViewVisibility(R.id.lock_graphic_primary_progress, View.GONE);
                remoteViews.setViewVisibility(R.id.lock_graphic_primary_icon, View.GONE);
                remoteViews.setTextViewText(R.id.lock_graphic_primary_value, "SIGN IN");
                remoteViews.setTextViewTextSize(R.id.lock_graphic_primary_value, TypedValue.COMPLEX_UNIT_SP, 11.0f);
                return remoteViews;
            }
            remoteViews.setViewVisibility(R.id.lock_graphic_primary_group, View.GONE);
            remoteViews.setViewVisibility(R.id.lock_graphic_secondary_group, View.GONE);
            return remoteViews;
        }
        int[] iArrGrantedSize = grantedSize(appWidgetManager, widgetId, shape);
        remoteViews.setImageViewBitmap(R.id.lock_graphic_image,
                SamsungLockGraphics.render(context, shape, style, primaryRemaining, primaryInner, secondaryRemaining,
                        secondaryInner, signedIn, iArrGrantedSize[0], iArrGrantedSize[1],
                        lockWidgetOptions, credits, primaryIconRes, secondaryIconRes));
        if (signedIn) {
            if (shape == Shape.SQUARE) {
                remoteViews.setViewVisibility(R.id.lock_graphic_center_value, View.GONE);
            } else {
                remoteViews.setViewVisibility(R.id.lock_graphic_primary_group, View.GONE);
                remoteViews.setViewVisibility(R.id.lock_graphic_secondary_group, View.GONE);
            }
            return remoteViews;
        }
        boolean showCredits = lockWidgetOptions.showResetCredits || lockWidgetOptions.showResetAction;
        if (shape == Shape.SQUARE) {
            if (signedIn) {
                centerText = squareGraphicText(binding, lockWidgetOptions, showCredits, credits);
            } else {
                centerText = "SIGN IN";
            }
            remoteViews.setTextViewText(R.id.lock_graphic_center_value, centerText);
            remoteViews.setTextViewTextSize(R.id.lock_graphic_center_value, TypedValue.COMPLEX_UNIT_SP, signedIn ? squareGraphicTextSize(binding.singleMetric(), lockWidgetOptions.showCountdown, showCredits) : 10.0f);
        } else if (!signedIn) {
            remoteViews.setViewVisibility(R.id.lock_graphic_primary_group, View.VISIBLE);
            remoteViews.setViewVisibility(R.id.lock_graphic_secondary_group, View.GONE);
            remoteViews.setTextViewText(R.id.lock_graphic_primary_value, "SIGN IN");
            remoteViews.setTextViewText(R.id.lock_graphic_primary_label, "");
            remoteViews.setTextViewTextSize(R.id.lock_graphic_primary_value, TypedValue.COMPLEX_UNIT_SP, 11.0f);
        } else {
            boolean showPrimary = binding.showPrimary;
            boolean showSecondary = binding.showSecondary;
            remoteViews.setViewVisibility(R.id.lock_graphic_primary_group, showPrimary ? View.VISIBLE : View.GONE);
            remoteViews.setViewVisibility(R.id.lock_graphic_secondary_group, showSecondary ? View.VISIBLE : View.GONE);
            remoteViews.setTextViewText(R.id.lock_graphic_primary_value, compactValue(primaryRemaining));
            remoteViews.setTextViewText(R.id.lock_graphic_secondary_value, compactValue(secondaryRemaining));
            String primaryLabel = binding.primary == null ? "5H" : binding.primary.label;
            String secondaryLabel = binding.secondary == null ? "W" : binding.secondary.label;
            remoteViews.setTextViewText(R.id.lock_graphic_primary_label, labelWithReset(primaryLabel, showCredits && showPrimary, credits));
            remoteViews.setTextViewText(R.id.lock_graphic_secondary_label, labelWithReset(secondaryLabel, showCredits && !showPrimary && showSecondary, credits));
            float f = binding.singleMetric() ? 20.0f : 17.0f;
            if (lockWidgetOptions.showCountdown) {
                f -= 2.0f;
            }
            float labelSize = binding.singleMetric() ? 9.5f : 8.0f;
            remoteViews.setTextViewTextSize(R.id.lock_graphic_primary_value, TypedValue.COMPLEX_UNIT_SP, f);
            remoteViews.setTextViewTextSize(R.id.lock_graphic_secondary_value, TypedValue.COMPLEX_UNIT_SP, f);
            remoteViews.setTextViewTextSize(R.id.lock_graphic_primary_label, TypedValue.COMPLEX_UNIT_SP, labelSize);
            remoteViews.setTextViewTextSize(R.id.lock_graphic_secondary_label, TypedValue.COMPLEX_UNIT_SP, labelSize);
        }
        return remoteViews;
    }

    private static String squareGraphicText(LockMeterBinding binding, LockWidgetOptions lockWidgetOptions, boolean showCredits, int credits) {
        String text;
        if (binding.singleMetric()) {
            String label = binding.primary == null ? "5H" : binding.primary.label;
            text = compactValue(binding.primaryRemaining) + "\n" + label;
        } else {
            String primaryLabel = binding.primary == null ? "5H" : binding.primary.label;
            String secondaryLabel = binding.secondary == null ? "W" : binding.secondary.label;
            text = primaryLabel + " " + compactValue(binding.primaryRemaining) + "\n"
                    + secondaryLabel + " " + compactValue(binding.secondaryRemaining);
        }
        return showCredits ? text + "\nR" + Math.max(0, credits) : text;
    }

    private static float squareGraphicTextSize(boolean singleMetric, boolean showCountdown, boolean showCredits) {
        if (singleMetric) {
            if (showCredits) {
                return 9.5f;
            }
            return showCountdown ? 11.5f : 12.5f;
        }
        if (showCredits) {
            return 8.1f;
        }
        return showCountdown ? 8.8f : 9.5f;
    }

    private static String labelWithReset(String label, boolean showCredits, int credits) {
        return showCredits ? label + " · R" + Math.max(0, credits) : label;
    }

    private static void applyCountdowns(RemoteViews remoteViews, Shape shape, Style style,
            LockWidgetOptions lockWidgetOptions, LockMeterBinding binding) {
        UsageWindow primaryWindow = binding.primary == null ? null : binding.primary.window;
        UsageWindow secondaryWindow = binding.secondary == null ? null : binding.secondary.window;
        if (style == Style.BARS || (shape == Shape.WIDE && (style == Style.RINGS || style == Style.DIALS))) {
            applyCountdown(remoteViews, R.id.lock_primary_countdown,
                    lockWidgetOptions.showCountdown && binding.showPrimary, primaryWindow);
            applyCountdown(remoteViews, R.id.lock_secondary_countdown,
                    lockWidgetOptions.showCountdown && binding.showSecondary, secondaryWindow);
        } else {
            UsageWindow window = binding.singleMetric()
                    ? primaryWindow
                    : earlierWindow(primaryWindow, secondaryWindow);
            applyCountdown(remoteViews, R.id.lock_countdown, lockWidgetOptions.showCountdown, window);
        }
    }

    private static UsageWindow earlierWindow(UsageWindow primary, UsageWindow secondary) {
        long now = System.currentTimeMillis();
        boolean showPrimary = primary != null && primary.showsResetCountdown();
        boolean showSecondary = secondary != null && secondary.showsResetCountdown();
        long primaryReset = showPrimary ? primary.resetAtMillis() : 0L;
        long secondaryReset = showSecondary ? secondary.resetAtMillis() : 0L;
        if (primaryReset > now) {
            return (secondaryReset <= now || primaryReset <= secondaryReset)
                    ? primary : secondary;
        }
        if (secondaryReset <= now) {
            return null;
        }
        return secondary;
    }

    private static void applyCountdown(RemoteViews remoteViews, int viewId, boolean enabled, UsageWindow window) {
        long now = System.currentTimeMillis();
        long resetAt = window == null ? 0L : window.resetAtMillis();
        if (!enabled || window == null || !window.showsResetCountdown()
                || resetAt <= now) {
            remoteViews.setViewVisibility(viewId, View.GONE);
            return;
        }
        long base = SystemClock.elapsedRealtime() + Math.max(1000L, resetAt - now);
        remoteViews.setViewVisibility(viewId, View.VISIBLE);
        remoteViews.setChronometer(viewId, base, null, true);
        remoteViews.setChronometerCountDown(viewId, true);
    }

    private static void updateComponent(Context context, AppWidgetManager appWidgetManager, Class<?> cls, Shape shape, Style style, Metric metric) {
        try {
            updateIds(context, appWidgetManager, appWidgetManager.getAppWidgetIds(new ComponentName(context, cls)), shape, style, metric);
        } catch (RuntimeException e) {
            Log.w("CodexMeterLock", "Unable to enumerate lock widgets", e);
        }
    }

    private static void applyOpenIntent(Context context, RemoteViews remoteViews, int viewId, int widgetId,
            Shape shape, Style style, LockWidgetOptions lockWidgetOptions, boolean signedIn, int credits,
            UsageProvider provider) {
        boolean opensReset = lockWidgetOptions.showResetAction && signedIn && credits > 0;
        String target = opensReset ? "reset" : "open";
        Uri tag = Uri.parse("codexmeter://widget/lock/v" + AppConstants.VERSION_CODE + "/"
                + widgetId + "/"
                + shape.name().toLowerCase(Locale.US) + "/"
                + style.name().toLowerCase(Locale.US)
                + "/" + target);
        // Open opens the app as the user left it, never a particular provider's tab.
        Intent intent = opensReset
                ? new Intent(context, (Class<?>) ResetCreditActivity.class)
                        .setAction("dev.bennett.codexmeter.action.LOCK_WIDGET_RESET")
                        .setData(tag)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                : AppOpenActivity.widgetIntent(context, tag);
        remoteViews.setOnClickPendingIntent(viewId, PendingIntent.getActivity(context, 82000 + widgetId + (shape.ordinal() * 1000) + (style.ordinal() * 100) + (opensReset ? 50000 : 0), intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
    }

    private static int graphicLayout(Shape shape, Style style) {
        return style == Style.RINGS ? shape == Shape.SQUARE ? R.layout.widget_lock_rings_square : R.layout.widget_lock_rings_wide : shape == Shape.SQUARE ? R.layout.widget_lock_dials_square : R.layout.widget_lock_dials_wide;
    }

    private static String numberText(boolean signedIn, LockMeterBinding binding, Shape shape,
            LockWidgetOptions lockWidgetOptions, int credits) {
        String text;
        if (!signedIn) {
            return shape == Shape.SQUARE ? "SIGN\nIN" : "SIGN IN";
        }
        String primaryLabel = binding.primary == null ? "5H" : binding.primary.label;
        String secondaryLabel = binding.secondary == null ? "W" : binding.secondary.label;
        if (binding.singleMetric()) {
            text = shape == Shape.SQUARE
                    ? primaryLabel + "\n" + value(binding.primaryRemaining)
                    : primaryLabel + " " + value(binding.primaryRemaining);
        } else if (shape == Shape.SQUARE) {
            text = primaryLabel + " " + compactValue(binding.primaryRemaining) + "\n"
                    + secondaryLabel + " " + compactValue(binding.secondaryRemaining);
        } else {
            text = primaryLabel + " " + value(binding.primaryRemaining) + "  ·  "
                    + secondaryLabel + " " + value(binding.secondaryRemaining);
        }
        if (lockWidgetOptions.showResetCredits || lockWidgetOptions.showResetAction) {
            return text + (shape == Shape.SQUARE ? "\nR" + Math.max(0, credits) : "  ·  R" + Math.max(0, credits));
        }
        return text;
    }

    private static float numberTextSize(Shape shape, boolean singleMetric, boolean showCredits) {
        return shape == Shape.WIDE ? singleMetric ? showCredits ? 14.0f : 16.0f : showCredits ? 12.0f : 14.0f
                : singleMetric ? showCredits ? 10.5f : 13.0f : showCredits ? 9.2f : 11.0f;
    }

    private static String contentDescription(String name, boolean signedIn, LockMeterBinding binding,
            Style style, LockWidgetOptions lockWidgetOptions, int credits) {
        if (!signedIn) {
            return name + ", sign in required";
        }
        StringBuilder description = new StringBuilder(name).append(' ')
                .append(styleLabel(style).toLowerCase(Locale.ROOT)).append(", ");
        if (binding.showPrimary) {
            String label = binding.primary == null ? "primary" : binding.primary.label;
            description.append(label).append(' ').append(value(binding.primaryRemaining))
                    .append(" remaining");
            appendInner(description, binding.primary);
        }
        if (binding.showPrimary && binding.showSecondary) {
            description.append(", ");
        }
        if (binding.showSecondary) {
            String label = binding.secondary == null ? "secondary" : binding.secondary.label;
            description.append(label).append(' ').append(value(binding.secondaryRemaining))
                    .append(" remaining");
            appendInner(description, binding.secondary);
        }
        if (lockWidgetOptions.showCountdown) {
            description.append(", live reset countdown");
        }
        if (lockWidgetOptions.showResetCredits || lockWidgetOptions.showResetAction) {
            description.append(", ").append(credits).append(" reset credit").append(credits == 1 ? "" : "s");
        }
        if (lockWidgetOptions.showResetAction && credits > 0) {
            description.append("; tap to open reset confirmation");
        }
        return description.toString();
    }

    private static void appendInner(StringBuilder sb, LockMeterSlot slot) {
        if (slot != null && slot.nested()) {
            sb.append(", five hour ").append(value(slot.inner)).append(" remaining");
        }
    }

    public static String styleLabel(Style style) {
        if (style == Style.RINGS) {
            return "Rings";
        }
        if (style == Style.DIALS) {
            return "Gauges";
        }
        return style == Style.BARS ? "Bars" : "Numbers";
    }

    private static String value(int i) {
        return i < 0 ? "—" : i + "%";
    }

    private static String compactValue(int i) {
        return i < 0 ? "—" : Integer.toString(i);
    }

    private static int progress(int i) {
        if (i < 0) {
            return 0;
        }
        return Math.max(0, Math.min(100, i));
    }

    private static int remaining(UsageWindow usageWindow) {
        if (usageWindow == null) {
            return -1;
        }
        return Math.max(0, Math.min(100, usageWindow.remainingPercent()));
    }

    private static ProviderSpec findSpec(AppWidgetManager appWidgetManager, Context context, int widgetId) {
        for (ProviderSpec spec : PROVIDERS) {
            try {
                for (int id : appWidgetManager.getAppWidgetIds(new ComponentName(context, spec.provider))) {
                    if (id == widgetId) {
                        return spec;
                    }
                }
            } catch (RuntimeException error) {
                DiagnosticLog.error(context, "widget", "lock_widget_ids_failed", error);
            }
        }
        return null;
    }

    /** The size the lock screen granted this widget, in dp, falling back to the shape's default. */
    private static int[] grantedSize(AppWidgetManager manager, int widgetId, Shape shape) {
        int defaultWidth = shape == Shape.SQUARE ? 56 : 124;
        int defaultHeight = 56;
        if (manager == null || widgetId == 0) {
            return new int[]{defaultWidth, defaultHeight};
        }
        try {
            Bundle options = manager.getAppWidgetOptions(widgetId);
            ArrayList<SizeF> sizes = widgetSizes(options);
            SizeF best = sizes == null || sizes.isEmpty() ? null : bestSize(sizes, shape);
            if (best != null) {
                return new int[]{Math.round(best.getWidth()), Math.round(best.getHeight())};
            }
            int width = options.getInt("appWidgetMinWidth", defaultWidth);
            int height = options.getInt("appWidgetMinHeight", defaultHeight);
            return new int[]{width > 0 ? width : defaultWidth, height > 0 ? height : defaultHeight};
        } catch (RuntimeException e) {
            Log.w("CodexMeterLock", "Unable to read lock widget size", e);
            return new int[]{defaultWidth, defaultHeight};
        }
    }

    // The typed getter is Android 13+; older versions only have the untyped one.
    @SuppressWarnings("deprecation")
    private static ArrayList<SizeF> widgetSizes(Bundle options) {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            return options.getParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, SizeF.class);
        }
        return options.getParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES);
    }

    private static SizeF bestSize(ArrayList<SizeF> sizes, Shape shape) {
        // Closest to the shape's aspect ratio, then closest to a 56 dp tall tile.
        float aspect = shape == Shape.SQUARE ? 1.0f : 2.2142856f;
        SizeF best = null;
        float bestScore = Float.MAX_VALUE;
        for (SizeF size : sizes) {
            if (size == null || size.getWidth() <= 0.0f || size.getHeight() <= 0.0f) {
                continue;
            }
            float score = (Math.abs((size.getWidth() / size.getHeight()) - aspect) * 100.0f)
                    + (Math.abs(size.getHeight() - 56.0f) * 0.2f);
            if (best == null || score < bestScore) {
                best = size;
                bestScore = score;
            }
        }
        return best;
    }

    private static Context application(Context context) {
        Context applicationContext = context.getApplicationContext();
        return applicationContext == null ? context : applicationContext;
    }
}
