package dev.bennett.codexmeter;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.os.PersistableBundle;

/* JADX INFO: loaded from: classes.dex */
public final class RefreshScheduler {
    private static final int IMMEDIATE_JOB_ID = 73101;
    private static final int PERIODIC_JOB_ID = 73100;
    static final String REASON_SHORT_PERIODIC = "short_periodic";
    private static final int RESET_JOB_ID = 73102;
    private static final int SHORT_JOB_ID_A = 73103;
    private static final int SHORT_JOB_ID_B = 73104;

    private RefreshScheduler() {
    }

    public static boolean schedulePeriodic(Context context) {
        boolean zSubmit = false;
        Context contextAppContext = appContext(context);
        if (contextAppContext == null) {
            return false;
        }
        if (!SecureTokenStore.isSignedIn(contextAppContext)) {
            cancelAll(contextAppContext);
            return true;
        }
        try {
            JobScheduler jobSchedulerScheduler = scheduler(contextAppContext);
            if (jobSchedulerScheduler == null) {
                AppPreferences.setSchedulerError(contextAppContext, "Android's background scheduler is unavailable.");
            } else {
                int refreshMinutes = AppPreferences.getRefreshMinutes(contextAppContext);
                jobSchedulerScheduler.cancel(PERIODIC_JOB_ID);
                jobSchedulerScheduler.cancel(SHORT_JOB_ID_A);
                jobSchedulerScheduler.cancel(SHORT_JOB_ID_B);
                if (refreshMinutes < 15) {
                    zSubmit = scheduleNextShort(contextAppContext, SHORT_JOB_ID_B);
                } else {
                    zSubmit = submit(contextAppContext, base(contextAppContext, PERIODIC_JOB_ID, "periodic").setPeriodic(((long) refreshMinutes) * 60 * 1000).setPersisted(true).build());
                }
            }
            return zSubmit;
        } catch (RuntimeException e) {
            return failed(contextAppContext, e);
        }
    }

    static boolean scheduleNextShort(Context context, int i) {
        boolean zSubmit = false;
        Context contextAppContext = appContext(context);
        if (contextAppContext == null) {
            return false;
        }
        if (!SecureTokenStore.isSignedIn(contextAppContext)) {
            return true;
        }
        int refreshMinutes = AppPreferences.getRefreshMinutes(contextAppContext);
        if (refreshMinutes >= 15) {
            return schedulePeriodic(contextAppContext);
        }
        try {
            JobScheduler jobSchedulerScheduler = scheduler(contextAppContext);
            if (jobSchedulerScheduler == null) {
                AppPreferences.setSchedulerError(contextAppContext, "Android's background scheduler is unavailable.");
            } else {
                int i2 = i == SHORT_JOB_ID_A ? SHORT_JOB_ID_B : SHORT_JOB_ID_A;
                jobSchedulerScheduler.cancel(i2);
                long j = ((long) refreshMinutes) * 60 * 1000;
                zSubmit = submit(contextAppContext, base(contextAppContext, i2, REASON_SHORT_PERIODIC).setMinimumLatency(j).setOverrideDeadline(j + 300000).build());
            }
            return zSubmit;
        } catch (RuntimeException e) {
            return failed(contextAppContext, e);
        }
    }

    public static boolean scheduleImmediate(Context context) {
        Context contextAppContext = appContext(context);
        if (contextAppContext == null) {
            return false;
        }
        if (!SecureTokenStore.isSignedIn(contextAppContext)) {
            WidgetRenderer.updateAll(contextAppContext);
            return true;
        }
        try {
            return submit(contextAppContext, base(contextAppContext, IMMEDIATE_JOB_ID, "immediate").setMinimumLatency(0L).setOverrideDeadline(5000L).build());
        } catch (RuntimeException e) {
            return failed(contextAppContext, e);
        }
    }

    public static boolean scheduleAtNextReset(Context context, UsageSnapshot usageSnapshot) {
        Context contextAppContext = appContext(context);
        if (contextAppContext == null || usageSnapshot == null) {
            return false;
        }
        long jCurrentTimeMillis = System.currentTimeMillis();
        long jNextResetMillis = usageSnapshot.nextResetMillis(jCurrentTimeMillis);
        if (jNextResetMillis <= jCurrentTimeMillis) {
            return false;
        }
        try {
            long jMax = Math.max(1000L, (jNextResetMillis - jCurrentTimeMillis) + 5000);
            return submit(contextAppContext, base(contextAppContext, RESET_JOB_ID, ResetConsumeResult.RESET).setMinimumLatency(jMax).setOverrideDeadline(jMax + 300000).build());
        } catch (RuntimeException e) {
            return failed(contextAppContext, e);
        }
    }

    public static void cancelAll(Context context) {
        Context contextAppContext = appContext(context);
        if (contextAppContext != null) {
            try {
                JobScheduler jobSchedulerScheduler = scheduler(contextAppContext);
                if (jobSchedulerScheduler != null) {
                    jobSchedulerScheduler.cancel(PERIODIC_JOB_ID);
                    jobSchedulerScheduler.cancel(IMMEDIATE_JOB_ID);
                    jobSchedulerScheduler.cancel(RESET_JOB_ID);
                    jobSchedulerScheduler.cancel(SHORT_JOB_ID_A);
                    jobSchedulerScheduler.cancel(SHORT_JOB_ID_B);
                    AppPreferences.setSchedulerError(contextAppContext, "");
                }
            } catch (RuntimeException e) {
                failed(contextAppContext, e);
            }
        }
    }

    private static boolean submit(Context context, JobInfo jobInfo) {
        JobScheduler jobSchedulerScheduler = scheduler(context);
        if (jobSchedulerScheduler == null) {
            AppPreferences.setSchedulerError(context, "Android's background scheduler is unavailable.");
            return false;
        }
        if (jobSchedulerScheduler.schedule(jobInfo) == 1) {
            AppPreferences.setSchedulerError(context, "");
            return true;
        }
        AppPreferences.setSchedulerError(context, "Android declined the background refresh request.");
        return false;
    }

    private static JobInfo.Builder base(Context context, int i, String str) {
        PersistableBundle persistableBundle = new PersistableBundle();
        persistableBundle.putString("reason", str);
        return new JobInfo.Builder(i, new ComponentName(context, (Class<?>) UsageRefreshJobService.class)).setRequiredNetworkType(1).setExtras(persistableBundle);
    }

    private static JobScheduler scheduler(Context context) {
        return (JobScheduler) context.getSystemService("jobscheduler");
    }

    private static Context appContext(Context context) {
        if (context == null) {
            return null;
        }
        Context applicationContext = context.getApplicationContext();
        return applicationContext != null ? applicationContext : context;
    }

    private static boolean failed(Context context, RuntimeException runtimeException) {
        String message = runtimeException.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = runtimeException.getClass().getSimpleName();
        }
        AppPreferences.setSchedulerError(context, "Background refresh: " + message);
        return false;
    }
}
