package dev.bennett.codexmeter;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Notices which app is in front so the live monitor can appear while a matched AI app is open
 * and go away after the user leaves it.
 *
 * <p>Events only say that something changed. Which app is in front is then read from the
 * window list itself: the app window the user is using (or, with the shade or keyboard over it,
 * the topmost app window). That answer does not depend on which events a launcher or firmware
 * happens to send, so opening from Recents, the home screen, a notification or a cold start all
 * behave the same, and going home always counts as leaving. Only the package name of that one
 * window is read; no text, no taps, no typing.
 */
public final class AppSessionService extends AccessibilityService {
    static final String TAG = "CodexAppSession";
    /** Leaving and returning within this window (share sheets, a quick app switch) keeps it up. */
    private static final long LEAVE_GRACE_MILLIS = 700L;
    /** Re-reads the window list once transitions settle, in case the last change sent no event. */
    private static final long SETTLE_RECHECK_MILLIS = 450L;

    private static AppSessionService running;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, Boolean> activityCache = new HashMap<>();
    private final Runnable leave = this::leaveNow;
    private boolean leavePending;
    private final Runnable recheck = () -> evaluate(null, null, 0L);
    /** The matched app in front, whether or not its monitor is showing. */
    private String currentPackage;
    private boolean debuggable;

    /** Whether the detector is connected right now. */
    static boolean isRunning() {
        return running != null;
    }

    /** Turns the detector off from inside the app when the user disables the feature. */
    static void disableIfRunning() {
        AppSessionService service = running;
        if (service != null) service.disableSelf();
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        running = this;
        debuggable = (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        DiagnosticLog.info(this, "app_session", "detector_connected");
        Log.i(TAG, "App detection connected");
        // The user may already be inside a matched app when detection comes on.
        evaluate(null, null, 0L);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            return;
        }
        CharSequence pkg = event.getPackageName();
        CharSequence cls = event.getClassName();
        evaluate(type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg != null
                        ? pkg.toString() : null,
                cls == null ? null : cls.toString(), event.getEventTime());
        handler.removeCallbacks(recheck);
        handler.postDelayed(recheck, SETTLE_RECHECK_MILLIS);
    }

    /**
     * Works out the app in front and acts on it. {@code eventPackage} is only a fallback for
     * the moment a cold-starting app shows its splash screen, before its own window is listed.
     */
    private void evaluate(String eventPackage, String eventClass, long eventUptime) {
        if (!AppSessionPreferences.isEnabled(this)) {
            if (currentPackage != null) leaveNow();
            return;
        }
        String front = frontAppPackage();
        boolean fromWindows = front != null;
        if (front == null && eventPackage != null && isActivity(eventPackage, eventClass)) {
            front = eventPackage;
        }
        if (front == null) return;
        AppSessionRules.Decision decision = AppSessionRules.decide(front, true,
                AppSessionPreferences.mapping(this), currentPackage);
        switch (decision.action) {
            case ENTER:
                cancelLeave();
                currentPackage = front;
                AppSessionPreferences.setDismissed(this, null);
                show(decision.provider, front, eventUptime, fromWindows);
                break;
            case STAY:
                cancelLeave();
                // Self-heal: if the monitor went away (expiry, another monitor ending) while the
                // app stayed in front, bring it back unless the user dismissed it here.
                if (!NowBarManager.isAppSessionFor(this, front)
                        && !front.equals(AppSessionPreferences.getDismissed(this))) {
                    UsageProvider provider = AppSessionPreferences.mapping(this).get(front);
                    if (provider != null) show(provider, front, eventUptime, fromWindows);
                }
                break;
            case LEAVE:
                if (!leavePending) {
                    leavePending = true;
                    handler.postDelayed(leave, LEAVE_GRACE_MILLIS);
                }
                break;
            case NONE:
            default:
                break;
        }
    }

    private void cancelLeave() {
        handler.removeCallbacks(leave);
        leavePending = false;
    }

    private void show(UsageProvider provider, String packageName, long eventUptime,
            boolean fromWindows) {
        long started = SystemClock.uptimeMillis();
        boolean shown = NowBarManager.startForApp(this, provider, packageName);
        if (debuggable) {
            long done = SystemClock.uptimeMillis();
            Log.i(TAG, "show " + packageName + " shown=" + shown
                    + " source=" + (fromWindows ? "windows" : "event")
                    + " event_to_handler_ms=" + (eventUptime > 0 ? started - eventUptime : -1)
                    + " post_ms=" + (done - started));
        }
    }

    private void leaveNow() {
        cancelLeave();
        String left = currentPackage;
        currentPackage = null;
        AppSessionPreferences.setDismissed(this, null);
        long started = SystemClock.uptimeMillis();
        NowBarManager.endAppSession(this);
        if (debuggable) {
            Log.i(TAG, "hide " + left + " end_ms=" + (SystemClock.uptimeMillis() - started));
        }
    }

    /**
     * The package of the app window the user is using: the active app window, or with the
     * shade, keyboard or another system overlay on top, the highest app window under it.
     * Null when no app window is listed (lock screen, a cold start's splash).
     */
    private String frontAppPackage() {
        List<AccessibilityWindowInfo> windows;
        try {
            windows = getWindows();
        } catch (RuntimeException unavailable) {
            return null;
        }
        if (windows == null || windows.isEmpty()) return null;
        AccessibilityWindowInfo best = null;
        for (AccessibilityWindowInfo window : windows) {
            if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
            if (window.isActive()) {
                best = window;
                break;
            }
            if (best == null || window.getLayer() > best.getLayer()) best = window;
        }
        if (best == null) return null;
        AccessibilityNodeInfo root = best.getRoot();
        if (root == null) return null;
        CharSequence pkg = root.getPackageName();
        return pkg == null ? null : pkg.toString();
    }

    /** True when the window is a full app screen rather than a popup or the keyboard. */
    private boolean isActivity(String packageName, String className) {
        if (className == null || className.isEmpty()) return false;
        String key = packageName + "/" + className;
        Boolean cached = activityCache.get(key);
        if (cached != null) return cached;
        boolean activity;
        try {
            getPackageManager().getActivityInfo(new ComponentName(packageName, className), 0);
            activity = true;
        } catch (PackageManager.NameNotFoundException | RuntimeException notAnActivity) {
            activity = false;
        }
        if (activityCache.size() > 256) activityCache.clear();
        activityCache.put(key, activity);
        return activity;
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        if (running == this) running = null;
        handler.removeCallbacks(recheck);
        if (currentPackage != null) leaveNow();
        super.onDestroy();
    }
}
