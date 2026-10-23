package dev.bennett.codexmeter;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import java.util.HashMap;
import java.util.Map;

/**
 * Notices which app is in front so the live monitor can appear while a matched AI app is open
 * and go away after the user leaves it. It listens only for "a new screen opened" events and
 * reads nothing but the package and screen class names: it cannot read screen content
 * ({@code canRetrieveWindowContent} is off), and it never taps or types.
 */
public final class AppSessionService extends AccessibilityService {
    /** Leaving and returning within this window (share sheets, a quick app switch) keeps it up. */
    private static final long LEAVE_GRACE_MILLIS = 1500L;

    private static AppSessionService running;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, Boolean> activityCache = new HashMap<>();
    private final Runnable leave = this::leaveNow;
    /** The matched app in front, whether or not its monitor is showing. */
    private String currentPackage;

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
        DiagnosticLog.info(this, "app_session", "detector_connected");
        android.util.Log.i("CodexAppSession", "App detection connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }
        if (!AppSessionPreferences.isEnabled(this)) {
            if (currentPackage != null) leaveNow();
            return;
        }
        CharSequence pkg = event.getPackageName();
        CharSequence cls = event.getClassName();
        if (pkg == null) return;
        String packageName = pkg.toString();
        boolean appWindow = isActivity(packageName, cls == null ? null : cls.toString());
        AppSessionRules.Decision decision = AppSessionRules.decide(packageName, appWindow,
                AppSessionPreferences.mapping(this), currentPackage);
        switch (decision.action) {
            case ENTER:
                handler.removeCallbacks(leave);
                currentPackage = packageName;
                NowBarManager.startForApp(this, decision.provider, packageName);
                break;
            case STAY:
                handler.removeCallbacks(leave);
                break;
            case LEAVE:
                handler.removeCallbacks(leave);
                handler.postDelayed(leave, LEAVE_GRACE_MILLIS);
                break;
            case NONE:
            default:
                break;
        }
    }

    private void leaveNow() {
        handler.removeCallbacks(leave);
        currentPackage = null;
        NowBarManager.endAppSession(this);
    }

    /**
     * True when the window is a full app screen. The keyboard, the notification shade, toasts
     * and popups report a view class instead of an activity, so pulling down the shade to look
     * at the monitor does not count as leaving the app.
     */
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
        if (currentPackage != null) leaveNow();
        super.onDestroy();
    }
}
