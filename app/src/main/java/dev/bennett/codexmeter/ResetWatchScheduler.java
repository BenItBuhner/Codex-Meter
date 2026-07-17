package dev.bennett.codexmeter;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import java.util.concurrent.TimeUnit;

/** Schedules best-effort 30- or 60-minute checks under Android battery policy. */
public final class ResetWatchScheduler {
    private static final int PERIODIC_JOB_ID = 73500;
    private static final int INITIAL_JOB_ID = 73501;
    private static final int MANUAL_JOB_ID = 73502;

    private ResetWatchScheduler() {
    }

    public static boolean ensureScheduled(Context context) {
        Context app = application(context);
        if (!AnnouncementPreferences.monitoringEnabled(app)
                || !SecureXTokenStore.isConnected(app)) {
            cancel(app);
            return true;
        }
        try {
            JobScheduler scheduler = scheduler(app);
            if (scheduler == null) return false;
            long period = TimeUnit.MINUTES.toMillis(
                    AnnouncementPreferences.intervalMinutes(app));
            long flex = Math.min(TimeUnit.MINUTES.toMillis(15), period / 3);
            JobInfo existing = scheduler.getPendingJob(PERIODIC_JOB_ID);
            boolean scheduled = true;
            if (existing == null || differs(existing.getIntervalMillis(), period)
                    || differs(existing.getFlexMillis(), flex)
                    || existing.getNetworkType() != JobInfo.NETWORK_TYPE_ANY
                    || !existing.isPersisted()) {
                JobInfo periodic = base(app, PERIODIC_JOB_ID)
                        .setPeriodic(period, flex)
                        .setPersisted(true)
                        .build();
                scheduled = scheduler.schedule(periodic) == JobScheduler.RESULT_SUCCESS;
            }
            if (AnnouncementPreferences.lastCheckMillis(app) == 0L
                    && scheduler.getPendingJob(INITIAL_JOB_ID) == null) {
                scheduled = scheduler.schedule(base(app, INITIAL_JOB_ID)
                        .setMinimumLatency(0L)
                        .setOverrideDeadline(TimeUnit.MINUTES.toMillis(2))
                        .build()) == JobScheduler.RESULT_SUCCESS && scheduled;
            }
            return scheduled;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    public static boolean requestImmediate(Context context) {
        Context app = application(context);
        if (!AnnouncementPreferences.monitoringEnabled(app)
                || !SecureXTokenStore.isConnected(app)) {
            return false;
        }
        try {
            JobScheduler scheduler = scheduler(app);
            if (scheduler == null) return false;
            scheduler.cancel(MANUAL_JOB_ID);
            return scheduler.schedule(base(app, MANUAL_JOB_ID)
                    .setMinimumLatency(0L)
                    .setOverrideDeadline(TimeUnit.MINUTES.toMillis(1))
                    .build()) == JobScheduler.RESULT_SUCCESS;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    public static void cancel(Context context) {
        try {
            JobScheduler scheduler = scheduler(application(context));
            if (scheduler != null) {
                scheduler.cancel(PERIODIC_JOB_ID);
                scheduler.cancel(INITIAL_JOB_ID);
                scheduler.cancel(MANUAL_JOB_ID);
            }
        } catch (RuntimeException ignored) {
        }
    }

    private static JobInfo.Builder base(Context context, int id) {
        return new JobInfo.Builder(id,
                new ComponentName(context, ResetWatchJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY);
    }

    private static JobScheduler scheduler(Context context) {
        return (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
    }

    private static boolean differs(long actual, long expected) {
        return Math.abs(actual - expected) > TimeUnit.MINUTES.toMillis(1);
    }

    private static Context application(Context context) {
        Context app = context.getApplicationContext();
        return app == null ? context : app;
    }
}
