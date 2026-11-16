package dev.bennett.codexmeter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which apps show the live monitor while they are open, and what to do when the app in front
 * changes. Pure Java so the self-tests can check it; {@link AppSessionService} feeds it.
 */
public final class AppSessionRules {
    /** An official app that is matched to a provider out of the box. */
    public static final class KnownApp {
        public final String packageName;
        public final String label;
        public final UsageProvider provider;

        KnownApp(String packageName, String label, UsageProvider provider) {
            this.packageName = packageName;
            this.label = label;
            this.provider = provider;
        }
    }

    /** What the session should do after the app in front changed. */
    public enum Action {
        /** Nothing to do: an overlay, the keyboard, or an unmatched app with no session. */
        NONE,
        /** The matched app is still in front; cancel any pending hide. */
        STAY,
        /** A matched app came to the front: show its provider. */
        ENTER,
        /** A matched app is no longer in front: hide after a short grace period. */
        LEAVE
    }

    /** Outcome of {@link #decide}. {@code provider} is set only for {@link Action#ENTER}. */
    public static final class Decision {
        public final Action action;
        public final UsageProvider provider;

        Decision(Action action, UsageProvider provider) {
            this.action = action;
            this.provider = provider;
        }
    }

    private static final KnownApp[] KNOWN = {
            new KnownApp("com.openai.chatgpt", "ChatGPT", UsageProvider.CODEX),
            new KnownApp("com.anthropic.claude", "Claude", UsageProvider.CLAUDE_CODE),
            new KnownApp("com.github.android", "GitHub", UsageProvider.COPILOT),
            new KnownApp("ai.x.grok", "Grok", UsageProvider.GROK),
            new KnownApp("com.moonshot.kimichat", "Kimi", UsageProvider.KIMI),
    };

    private AppSessionRules() {
    }

    /** Official apps matched to {@code provider} by default. */
    public static List<KnownApp> knownApps(UsageProvider provider) {
        List<KnownApp> apps = new ArrayList<>();
        for (KnownApp app : KNOWN) {
            if (app.provider == provider) apps.add(app);
        }
        return apps;
    }

    /** The default entry for {@code packageName}, or null. */
    public static KnownApp known(String packageName) {
        if (packageName == null) return null;
        for (KnownApp app : KNOWN) {
            if (app.packageName.equals(packageName)) return app;
        }
        return null;
    }

    /**
     * The effective app-to-provider map: the official apps, then apps the user added (an added
     * app may also re-point an official one), minus every package the user switched off.
     *
     * @param added package name to provider id
     */
    public static Map<String, UsageProvider> resolve(Map<String, String> added,
            Collection<String> off) {
        Map<String, UsageProvider> mapping = new LinkedHashMap<>();
        for (KnownApp app : KNOWN) mapping.put(app.packageName, app.provider);
        if (added != null) {
            for (Map.Entry<String, String> entry : added.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isEmpty()) continue;
                mapping.put(entry.getKey(), UsageProvider.fromId(entry.getValue()));
            }
        }
        if (off != null) {
            for (String packageName : off) mapping.remove(packageName);
        }
        return Collections.unmodifiableMap(mapping);
    }

    /**
     * Decides what to do when a window comes to the front.
     *
     * @param packageName the package that owns the new window
     * @param isAppWindow true only for a full app screen (an activity), false for the
     *        keyboard, the notification shade, dialogs and other overlays. Only unmatched
     *        packages need it: an overlay from another app must not count as leaving.
     * @param currentPackage the matched app the session is tracking, or null
     */
    public static Decision decide(String packageName, boolean isAppWindow,
            Map<String, UsageProvider> mapping, String currentPackage) {
        if (packageName == null) return new Decision(Action.NONE, null);
        UsageProvider provider = mapping == null ? null : mapping.get(packageName);
        // A matched app counts from any of its windows: reopening it from Recents or the home
        // screen often reports the app's window class rather than an activity name.
        if (provider == null && !isAppWindow) return new Decision(Action.NONE, null);
        if (provider != null) {
            return packageName.equals(currentPackage)
                    ? new Decision(Action.STAY, null)
                    : new Decision(Action.ENTER, provider);
        }
        return currentPackage == null
                ? new Decision(Action.NONE, null)
                : new Decision(Action.LEAVE, null);
    }

    /**
     * The session between window changes, with its grace period as state rather than a pending
     * callback: IDLE, SHOWING an app, or LEAVING it at a set time unless it comes back first.
     * Times are {@code SystemClock.uptimeMillis()} values the caller passes in.
     */
    public static final class Session {
        public enum Phase { IDLE, SHOWING, LEAVING }

        /** Leaving and returning within this window (share sheets, a quick app switch) keeps it up. */
        public static final long LEAVE_GRACE_MILLIS = 600L;

        private Phase phase = Phase.IDLE;
        private String app;
        private long leaveAt;

        public Phase phase() {
            return phase;
        }

        /** The matched app being tracked, still set while LEAVING; null when IDLE. */
        public String app() {
            return app;
        }

        /** When a LEAVING session ends; meaningless in other phases. */
        public long leaveAt() {
            return leaveAt;
        }

        /** A matched app came to the front. */
        public void enter(String packageName) {
            phase = Phase.SHOWING;
            app = packageName;
        }

        /** The tracked app is still in front: a pending leave is called off. */
        public void stay() {
            if (phase == Phase.LEAVING) phase = Phase.SHOWING;
        }

        /**
         * The tracked app went away. Returns the time to check back, or -1 when nothing needs
         * scheduling (no app is tracked, or a leave is already counting down and keeps its time).
         */
        public long startLeaving(long now) {
            if (phase != Phase.SHOWING) return -1;
            phase = Phase.LEAVING;
            leaveAt = now + LEAVE_GRACE_MILLIS;
            return leaveAt;
        }

        /**
         * A scheduled check came due. Returns the app that left, now IDLE, or null when the app
         * came back or the check is early or stale.
         */
        public String graceElapsed(long now) {
            if (phase != Phase.LEAVING || now < leaveAt) return null;
            return end();
        }

        /** Ends the session at once (the feature was switched off, the service stopped). */
        public String end() {
            String left = app;
            phase = Phase.IDLE;
            app = null;
            return left;
        }
    }
}
