package dev.bennett.codexmeter;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Notices which app is in front so the live monitor can appear while a matched AI app is open
 * and go away after the user leaves it.
 *
 * <p>Two sources feed one decision. When a matched app's screen opens, its window event names
 * it and the monitor is shown straight away. Every event also triggers a read of the window
 * list itself, which says which app window the user is really in. That read does not depend
 * on which events a launcher or firmware happens to send, so opening from Recents, the home
 * screen, a notification or a cold start all behave the same, and going home always counts as
 * leaving. The read runs on its own thread so a busy app never delays the main thread, and
 * each window's package is remembered so a read rarely needs to ask the app. Only package
 * names are read; no text, no taps, no typing.
 */
public final class AppSessionService extends AccessibilityService {
    static final String TAG = "CodexAppSession";
    /** Leaving and returning within this window (share sheets, a quick app switch) keeps it up. */
    private static final long LEAVE_GRACE_MILLIS = 600L;
    /** Re-reads the window list once transitions settle, in case the last change sent no event. */
    private static final long SETTLE_RECHECK_MILLIS = 400L;

    private static AppSessionService running;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private HandlerThread workerThread;
    private Handler worker;
    /** Window id to the package that owns it; filled from events and from window reads. */
    private final Map<Integer, String> windowPackages = new ConcurrentHashMap<>();
    private final Map<String, Boolean> activityCache = new HashMap<>();
    private final Runnable leave = this::leaveNow;
    private boolean leavePending;
    private final Runnable readNow = () -> readWindows(false);
    private final Runnable readSettled = () -> readWindows(true);
    /** Bumped on every event that decided something itself; older window reads are stale. */
    private volatile int eventGeneration;
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
        if (workerThread == null) {
            workerThread = new HandlerThread("AppSessionWindows");
            workerThread.start();
            worker = new Handler(workerThread.getLooper());
        }
        DiagnosticLog.info(this, "app_session", "detector_connected");
        Log.i(TAG, "App detection connected");
        // The user may already be inside a matched app when detection comes on.
        scheduleRead();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            return;
        }
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence pkg = event.getPackageName();
            CharSequence cls = event.getClassName();
            int windowId = event.getWindowId();
            if (pkg != null && windowId != -1) windowPackages.put(windowId, pkg.toString());
            if (pkg != null && cls != null) {
                onScreenOpened(pkg.toString(), cls.toString(), event.getEventTime());
            }
        }
        scheduleRead();
    }

    /**
     * Fast path: a full app screen just opened. A matched app shows at once, without waiting
     * for the window read. Anything else waits for the read, which knows what is underneath.
     */
    private void onScreenOpened(String packageName, String className, long eventUptime) {
        if (!AppSessionPreferences.isEnabled(this)) return;
        if (AppSessionPreferences.mapping(this).get(packageName) == null) return;
        if (!isActivity(packageName, className)) return;
        eventGeneration++;
        apply(packageName, eventUptime, "event");
    }

    private void scheduleRead() {
        if (worker == null) return;
        worker.removeCallbacks(readNow);
        worker.post(readNow);
        worker.removeCallbacks(readSettled);
        worker.postDelayed(readSettled, SETTLE_RECHECK_MILLIS);
    }

    /** Runs on the worker thread. */
    private void readWindows(boolean settled) {
        int generation = eventGeneration;
        long started = SystemClock.uptimeMillis();
        String front = frontAppPackage();
        if (front == null) return;
        handler.post(() -> {
            // An event decided after this read began; a newer read is already queued.
            if (running != this || generation != eventGeneration) return;
            apply(front, started, settled ? "settled" : "windows");
        });
    }

    /** Acts on the app in front. Main thread only. */
    private void apply(String front, long sinceUptime, String source) {
        if (!AppSessionPreferences.isEnabled(this)) {
            if (currentPackage != null) leaveNow();
            return;
        }
        Map<String, UsageProvider> mapping = AppSessionPreferences.mapping(this);
        AppSessionRules.Decision decision = AppSessionRules.decide(front, true, mapping,
                currentPackage);
        switch (decision.action) {
            case ENTER:
                cancelLeave();
                currentPackage = front;
                AppSessionPreferences.setDismissed(this, null);
                show(decision.provider, front, sinceUptime, source);
                break;
            case STAY:
                cancelLeave();
                // Self-heal: if the monitor went away (expiry, another monitor ending) while the
                // app stayed in front, bring it back unless the user dismissed it here.
                if (!NowBarManager.isAppSessionFor(this, front)
                        && !front.equals(AppSessionPreferences.getDismissed(this))) {
                    UsageProvider provider = mapping.get(front);
                    if (provider != null) show(provider, front, sinceUptime, source);
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

    private void show(UsageProvider provider, String packageName, long sinceUptime,
            String source) {
        long started = SystemClock.uptimeMillis();
        boolean shown = NowBarManager.startForApp(this, provider, packageName);
        if (debuggable) {
            long done = SystemClock.uptimeMillis();
            Log.i(TAG, "show " + packageName + " shown=" + shown + " source=" + source
                    + " wait_ms=" + (sinceUptime > 0 ? started - sinceUptime : -1)
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
     * Null when no app window is listed (lock screen, a cold start's splash). Worker thread.
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
        int id = best.getId();
        String known = windowPackages.get(id);
        if (known != null) return known;
        // Unknown window: ask it once. This waits on the app, which is why it is off the main
        // thread; without prefetching it returns just the root node.
        AccessibilityNodeInfo root;
        try {
            root = Build.VERSION.SDK_INT >= 33 ? best.getRoot(0) : best.getRoot();
        } catch (RuntimeException unavailable) {
            return null;
        }
        if (root == null) return null;
        CharSequence pkg = root.getPackageName();
        if (pkg == null) return null;
        String packageName = pkg.toString();
        if (windowPackages.size() > 64) {
            Set<Integer> live = new HashSet<>();
            for (AccessibilityWindowInfo window : windows) live.add(window.getId());
            windowPackages.keySet().retainAll(live);
        }
        windowPackages.put(id, packageName);
        return packageName;
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
        if (worker != null) worker.removeCallbacksAndMessages(null);
        if (workerThread != null) workerThread.quitSafely();
        handler.removeCallbacksAndMessages(null);
        if (currentPackage != null) leaveNow();
        super.onDestroy();
    }
}
