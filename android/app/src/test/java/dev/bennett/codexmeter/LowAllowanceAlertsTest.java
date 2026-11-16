package dev.bennett.codexmeter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowNotificationManager;

/**
 * Low-allowance alerts reached Codex only. Every provider's saved usage now goes through the
 * same level and limit choice, on that provider's own windows.
 */
@RunWith(RobolectricTestRunner.class)
public final class LowAllowanceAlertsTest {
    private Application app;
    private ShadowNotificationManager notifications;

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        Shadows.shadowOf(app).grantPermissions("android.permission.POST_NOTIFICATIONS");
        notifications = Shadows.shadowOf(
                (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE));
        ResetAlertPreferences.save(app, ResetAlertPreferences.STYLE_NOTIFICATION,
                ResetAlertPreferences.METRIC_BOTH, 25);
    }

    private static UsageSnapshot monthly(int usedPercent) {
        long now = System.currentTimeMillis();
        UsageWindow month = new UsageWindow(usedPercent, TimeUnit.DAYS.toSeconds(30), 0L,
                (now + TimeUnit.DAYS.toMillis(12)) / 1000L);
        return new UsageSnapshot("Pro", true, false, null, null, month,
                Collections.emptyList(), null, -1, now);
    }

    @Test
    public void aLowCursorMonthAlertsOnceNamingCursor() {
        ProviderPreferences.saveSnapshot(app, UsageProvider.CURSOR, monthly(80));
        assertEquals(1, notifications.size());
        Notification posted = notifications.getAllNotifications().get(0);
        assertEquals("Monthly Cursor usage is low",
                posted.extras.getString(Notification.EXTRA_TITLE));
        ProviderPreferences.saveSnapshot(app, UsageProvider.CURSOR, monthly(85));
        assertEquals("the same window alerts once", 1, notifications.size());
    }

    @Test
    public void aCursorMonthAboveTheLevelStaysQuiet() {
        ProviderPreferences.saveSnapshot(app, UsageProvider.CURSOR, monthly(40));
        assertTrue(notifications.getAllNotifications().isEmpty());
    }

    @Test
    public void theFiveHourOnlyChoiceSkipsMonthlyPlans() {
        ResetAlertPreferences.save(app, ResetAlertPreferences.STYLE_NOTIFICATION,
                ResetAlertPreferences.METRIC_FIVE_HOUR, 25);
        ProviderPreferences.saveSnapshot(app, UsageProvider.CURSOR, monthly(90));
        assertTrue(notifications.getAllNotifications().isEmpty());
    }
}
