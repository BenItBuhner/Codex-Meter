package dev.bennett.codexmeter;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Posts at most one high-confidence, deduplicated reset-watch notification per poll. */
public final class ResetWatchNotificationManager {
    private static final String CHANNEL_ID = "codex_reset_watch_v1";
    private static final String PREFS = "codex_meter_reset_watch_notifications_v1";
    private static final String KEY_NOTIFIED = "notified_post_ids";
    private static final int NOTIFICATION_BASE = 74700;

    private ResetWatchNotificationManager() {
    }

    public static void notifyNew(Context context, List<XPost> posts, boolean hadBaseline) {
        if (context == null || posts == null || posts.isEmpty() || !hadBaseline
                || !AnnouncementPreferences.notificationsEnabled(context)) {
            return;
        }
        Set<String> notified = new HashSet<>(state(context)
                .getStringSet(KEY_NOTIFIED, new HashSet<>()));
        XPost best = null;
        for (XPost post : posts) {
            if (post == null || notified.contains(post.id) || !post.signal.isActionable()) {
                continue;
            }
            if (best == null || post.signal.likelihood > best.signal.likelihood
                    || (post.signal.likelihood == best.signal.likelihood
                    && XTimelineParser.compareIds(post.id, best.id) > 0)) {
                best = post;
            }
        }
        if (best == null || !post(context, best)) return;
        for (XPost post : posts) {
            if (post != null && post.signal.isActionable()) notified.add(post.id);
        }
        while (notified.size() > 50) notified.remove(notified.iterator().next());
        state(context).edit().putStringSet(KEY_NOTIFIED, notified).apply();
    }

    public static void ensureChannel(Context context) {
        NotificationManager manager = manager(context);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "Codex reset watch", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("High-confidence reset and reset-bank posts from @thsottiaux");
        manager.createNotificationChannel(channel);
    }

    public static void clearState(Context context) {
        if (context != null) state(context).edit().clear().apply();
    }

    private static boolean post(Context context, XPost post) {
        NotificationManager manager = manager(context);
        if (manager == null) return false;
        ensureChannel(context);
        if (!manager.areNotificationsEnabled()
                || (Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != PackageManager.PERMISSION_GRANTED)) {
            return false;
        }
        NotificationChannel channel = manager.getNotificationChannel(CHANNEL_ID);
        if (channel == null || channel.getImportance() == NotificationManager.IMPORTANCE_NONE) {
            return false;
        }
        int id = NOTIFICATION_BASE + Math.floorMod(post.id.hashCode(), 100_000);
        PendingIntent content = PendingIntent.getActivity(context, id,
                new Intent(context, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                                | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent openX = PendingIntent.getActivity(context, id + 10_000,
                new Intent(Intent.ACTION_VIEW, Uri.parse(post.url))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String text = post.signal.likelihoodLabel() + " · "
                + (post.text.length() <= 240 ? post.text : post.text.substring(0, 240));
        Notification notification = new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_reset_notification)
                .setContentTitle(post.signal.title())
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(content)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_menu_view, "Open on X", openX).build())
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_SOCIAL)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setShowWhen(true)
                .build();
        manager.notify(id, notification);
        return true;
    }

    private static NotificationManager manager(Context context) {
        return (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    private static SharedPreferences state(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
