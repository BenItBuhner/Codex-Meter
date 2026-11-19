package dev.bennett.codexmeter;

import android.app.Activity;
import android.app.Application;
import android.content.ComponentCallbacks2;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.atomic.AtomicBoolean;

/** Installs opt-in process and screen lifecycle diagnostics without touching normal app data. */
public final class CodexMeterApplication extends Application
        implements Application.ActivityLifecycleCallbacks {
    /** How long after the first screen starts the widgets are redrawn, so it stays off cold start. */
    static final long WIDGET_REDRAW_DELAY_MS = 2_000L;
    private final AtomicBoolean widgetsRedrawn = new AtomicBoolean();

    @Override
    public void onCreate() {
        super.onCreate();
        DiagnosticLog.install(this);
        registerActivityLifecycleCallbacks(this);
        DiagnosticLog.info(this, "process", "application_started");
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        DiagnosticLog.info(this, "process", "memory_trimmed", "level", level,
                "ui_hidden", level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN);
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        DiagnosticLog.warn(this, "process", "low_memory");
    }

    @Override
    public void onActivityCreated(Activity activity, Bundle state) {
        DiagnosticLog.info(this, "screen", "created",
                "activity", activity.getClass().getSimpleName(),
                "restored", state != null);
    }

    @Override
    public void onActivityResumed(Activity activity) {
        DiagnosticLog.info(this, "screen", "resumed",
                "activity", activity.getClass().getSimpleName());
    }

    @Override
    public void onActivityStopped(Activity activity) {
        DiagnosticLog.info(this, "screen", "stopped",
                "activity", activity.getClass().getSimpleName(),
                "changing_configuration", activity.isChangingConfigurations());
    }

    @Override
    public void onActivityStarted(Activity activity) {
        DesignChrome.install(activity);
        redrawWidgetsOnce();
    }

    /**
     * Redraws the home and lock-screen widgets once per process, shortly after the first screen
     * shows. A force stop (from Settings, or an installer) cancels every PendingIntent the app
     * made, so until a widget is drawn again its taps do nothing; the next scheduled refresh can
     * be far off, and the stop cancels that job too.
     */
    private void redrawWidgetsOnce() {
        if (!widgetsRedrawn.compareAndSet(false, true)) return;
        new Handler(Looper.getMainLooper()).postDelayed(() -> new Thread(() -> {
            DiagnosticLog.info(this, "widget", "redraw_on_start");
            WidgetRenderer.updateAll(this);
        }, "widget-redraw").start(), WIDGET_REDRAW_DELAY_MS);
    }

    @Override
    public void onActivityPaused(Activity activity) {
    }

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle state) {
    }

    @Override
    public void onActivityDestroyed(Activity activity) {
    }
}
