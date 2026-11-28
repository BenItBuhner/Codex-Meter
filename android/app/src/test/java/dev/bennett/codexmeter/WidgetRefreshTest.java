package dev.bennett.codexmeter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.Context;
import android.content.Intent;
import android.os.PersistableBundle;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * A widget's Refresh button fetches the providers that widget shows even when their readings
 * are only a minute old. It used to go through the background schedule, which skips anything
 * fetched in the last five minutes, so a tap right after a refresh did nothing (the same bug
 * the live monitor's button had before PR 300).
 */
@RunWith(RobolectricTestRunner.class)
public final class WidgetRefreshTest {
    private static final int WIDGET_ID = 42;
    private Application app;

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        // Demo providers refresh without a network or tokens, which the JVM has neither of.
        app.getSharedPreferences("codex_meter_demo", Context.MODE_PRIVATE).edit()
                .putString("providers", "claude_code,cursor").commit();
    }

    @Test
    public void tapSchedulesARefreshOfEveryProviderTheWidgetShows() {
        ProviderPreferences.setWidgetProviders(app, WIDGET_ID,
                Arrays.asList(UsageProvider.CLAUDE_CODE, UsageProvider.CURSOR));

        new WidgetRefreshReceiver().onReceive(app,
                new Intent(app, WidgetRefreshReceiver.class)
                        .setAction(AppConstants.ACTION_REFRESH_WIDGET)
                        .putExtra("appWidgetId", WIDGET_ID));

        JobInfo job = userRefreshJob();
        assertNotNull("the tap schedules a user refresh, not the five-minute-gated one", job);
        assertEquals(Arrays.asList(UsageProvider.CLAUDE_CODE, UsageProvider.CURSOR),
                RefreshScheduler.userRefreshTargets(job.getExtras()));
    }

    @Test
    public void widgetTapNeverReplacesAPendingLiveMonitorTap() {
        ProviderPreferences.setWidgetProviders(app, WIDGET_ID,
                Collections.singletonList(UsageProvider.CURSOR));
        assertTrue(RefreshScheduler.scheduleUserRefresh(app, UsageProvider.CLAUDE_CODE));
        assertTrue(RefreshScheduler.scheduleWidgetRefresh(app,
                ProviderPreferences.getWidgetProviders(app, WIDGET_ID)));

        int userRefreshJobs = 0;
        for (JobInfo job : scheduler().getAllPendingJobs()) {
            if (RefreshScheduler.REASON_USER_REFRESH.equals(job.getExtras().getString("reason"))) {
                userRefreshJobs++;
            }
        }
        assertEquals("both taps stay queued", 2, userRefreshJobs);
    }

    @Test
    public void refreshFetchesReadingsYoungerThanFiveMinutes() {
        long minuteAgo = System.currentTimeMillis() - 60_000L;
        DemoMode.seed(app, UsageProvider.CLAUDE_CODE, minuteAgo);
        DemoMode.seed(app, UsageProvider.CURSOR, minuteAgo);

        // The background path leaves a one-minute-old reading alone, which is right for it.
        ClaudeUsageApi.refreshInBackgroundIfDue(app, true);
        assertEquals(minuteAgo, fetchedAt(UsageProvider.CLAUDE_CODE));

        UsageRefreshJobService.runUserRefresh(app,
                Arrays.asList(UsageProvider.CLAUDE_CODE, UsageProvider.CURSOR));
        assertTrue(fetchedAt(UsageProvider.CLAUDE_CODE) > minuteAgo);
        assertTrue(fetchedAt(UsageProvider.CURSOR) > minuteAgo);
    }

    @Test
    public void olderSingleProviderJobsStillRead() {
        PersistableBundle extras = new PersistableBundle();
        extras.putString(RefreshScheduler.EXTRA_PROVIDER, "claude_code");
        assertEquals(Collections.singletonList(UsageProvider.CLAUDE_CODE),
                RefreshScheduler.userRefreshTargets(extras));
        List<UsageProvider> none = RefreshScheduler.userRefreshTargets(null);
        assertTrue(none.isEmpty());
    }

    private long fetchedAt(UsageProvider provider) {
        return ProviderPreferences.loadSnapshot(app, provider).fetchedAtMillis;
    }

    private JobScheduler scheduler() {
        return (JobScheduler) app.getSystemService(Context.JOB_SCHEDULER_SERVICE);
    }

    private JobInfo userRefreshJob() {
        for (JobInfo job : scheduler().getAllPendingJobs()) {
            if (RefreshScheduler.REASON_USER_REFRESH.equals(job.getExtras().getString("reason"))) {
                return job;
            }
        }
        return null;
    }
}
