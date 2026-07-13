package dev.bennett.codexmeter;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import java.util.concurrent.TimeUnit;

/** Schedules low-frequency GitHub checks under Android's battery and network policy. */
public final class ReleaseUpdateScheduler {
    private static final int PERIODIC_JOB_ID = 73400;
    private static final int INITIAL_JOB_ID = 73401;
    private static final long PERIOD = TimeUnit.DAYS.toMillis(1);
    private static final long FLEX = TimeUnit.HOURS.toMillis(6);

    private ReleaseUpdateScheduler() {
    }

    public static boolean ensureScheduled(Context context) {
        Context app = application(context);
        if (!UpdatePreferences.automaticChecks(app)) {
            cancel(app);
            return true;
        }
        try {
            JobScheduler scheduler = (JobScheduler) app.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (scheduler == null) {
                return false;
            }
            boolean scheduled = true;
            JobInfo existing = scheduler.getPendingJob(PERIODIC_JOB_ID);
            if (existing == null || existing.getIntervalMillis() != PERIOD
                    || existing.getFlexMillis() != FLEX
                    || existing.getNetworkType() != JobInfo.NETWORK_TYPE_ANY
                    || !existing.isPersisted()) {
                JobInfo periodic = base(app, PERIODIC_JOB_ID)
                        .setPeriodic(PERIOD, FLEX)
                        .setPersisted(true)
                        .build();
                scheduled = scheduler.schedule(periodic) == JobScheduler.RESULT_SUCCESS;
            }
            if (UpdatePreferences.lastCheckMillis(app) == 0L
                    && scheduler.getPendingJob(INITIAL_JOB_ID) == null) {
                JobInfo initial = base(app, INITIAL_JOB_ID)
                        .setMinimumLatency(0L)
                        .setOverrideDeadline(TimeUnit.MINUTES.toMillis(5))
                        .build();
                scheduled = scheduler.schedule(initial) == JobScheduler.RESULT_SUCCESS
                        && scheduled;
            }
            return scheduled;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    public static void cancel(Context context) {
        Context app = application(context);
        try {
            JobScheduler scheduler = (JobScheduler) app.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (scheduler != null) {
                scheduler.cancel(PERIODIC_JOB_ID);
                scheduler.cancel(INITIAL_JOB_ID);
            }
        } catch (RuntimeException ignored) {
        }
    }

    private static JobInfo.Builder base(Context context, int jobId) {
        return new JobInfo.Builder(jobId,
                new ComponentName(context, ReleaseUpdateJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY);
    }

    private static Context application(Context context) {
        Context app = context.getApplicationContext();
        return app == null ? context : app;
    }
}
