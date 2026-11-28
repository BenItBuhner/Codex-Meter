package dev.bennett.codexmeter;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.os.PersistableBundle;
import java.util.Calendar;

/* JADX INFO: loaded from: classes.dex */
public final class RefreshScheduler {
    private static final int IMMEDIATE_JOB_ID = 73101;
    private static final int PERIODIC_JOB_ID = 73100;
    static final String REASON_ADAPTIVE = "adaptive";
    static final String REASON_SHORT_PERIODIC = "short_periodic";
    private static final int RESET_JOB_ID = 73102;
    private static final int SHORT_JOB_ID_A = 73103;
    private static final int SHORT_JOB_ID_B = 73104;
    private static final int REFILL_CHECK_JOB_ID = 73105;
    private static final int USER_REFRESH_JOB_ID = 73106;
    /** A widget's Refresh button; its own id, so it never replaces a live monitor tap. */
    private static final int WIDGET_REFRESH_JOB_ID = 73107;
    /** The user tapped Refresh outside the app: fetch one provider now, however fresh it is. */
    static final String REASON_USER_REFRESH = "user_refresh";
    static final String EXTRA_PROVIDER = "provider";
    /** A second look at a reading that showed a limit refilled early (see {@link RefillGuard}). */
    static final String REASON_REFILL_CHECK = "refill_check";

    private RefreshScheduler() {
    }

    /** Background refresh runs while Codex or any other provider is signed in. */
    static boolean anySignedIn(Context context) {
        for (UsageProvider provider : UsageProvider.values()) {
            if (SecureTokenStore.isSignedIn(context, provider)) {
                return true;
            }
        }
        return false;
    }

    public static boolean schedulePeriodic(Context context) {
        boolean zSubmit = false;
        Context contextAppContext = appContext(context);
        if (contextAppContext == null) {
            return false;
        }
        if (!anySignedIn(contextAppContext)) {
            DiagnosticLog.info(contextAppContext, "scheduler",
                    "refresh_schedule_skipped_signed_out");
            cancelAll(contextAppContext);
            return true;
        }
        try {
            JobScheduler jobSchedulerScheduler = scheduler(contextAppContext);
            if (jobSchedulerScheduler == null) {
                AppPreferences.setSchedulerError(contextAppContext, "Android's background scheduler is unavailable.");
            } else {
                jobSchedulerScheduler.cancel(PERIODIC_JOB_ID);
                jobSchedulerScheduler.cancel(SHORT_JOB_ID_A);
                jobSchedulerScheduler.cancel(SHORT_JOB_ID_B);
                if (AppPreferences.getAutomaticRefresh(contextAppContext)) {
                    DiagnosticLog.info(contextAppContext, "scheduler",
                            "refresh_schedule_requested",
                            "mode", "adaptive",
                            "minutes", effectiveRefreshMinutes(contextAppContext));
                    zSubmit = scheduleNextChained(contextAppContext, SHORT_JOB_ID_B,
                            REASON_ADAPTIVE);
                } else {
                    int refreshMinutes = AppPreferences.getRefreshMinutes(contextAppContext);
                    DiagnosticLog.info(contextAppContext, "scheduler",
                            "refresh_schedule_requested",
                            "mode", "fixed",
                            "minutes", refreshMinutes);
                    if (refreshMinutes < 15) {
                        zSubmit = scheduleNextChained(contextAppContext, SHORT_JOB_ID_B,
                                REASON_SHORT_PERIODIC);
                    } else {
                        zSubmit = submit(contextAppContext,
                                base(contextAppContext, PERIODIC_JOB_ID, "periodic")
                                        .setPeriodic(((long) refreshMinutes) * 60 * 1000)
                                        .setPersisted(true)
                                        .build());
                    }
                }
            }
            return zSubmit;
        } catch (RuntimeException e) {
            return failed(contextAppContext, e);
        }
    }

    static boolean scheduleNextShort(Context context, int i) {
        Context contextAppContext = appContext(context);
        if (contextAppContext == null) {
            return false;
        }
        if (!anySignedIn(contextAppContext)) {
            return true;
        }
        if (!AppPreferences.getAutomaticRefresh(contextAppContext)
                && AppPreferences.getRefreshMinutes(contextAppContext) >= 15) {
            return schedulePeriodic(contextAppContext);
        }
        return scheduleNextChained(contextAppContext, i,
                AppPreferences.getAutomaticRefresh(contextAppContext)
                        ? REASON_ADAPTIVE : REASON_SHORT_PERIODIC);
    }

    private static boolean scheduleNextChained(Context context, int previousJobId,
            String reason) {
        boolean submitted = false;
        int refreshMinutes = effectiveRefreshMinutes(context);
        try {
            JobScheduler jobs = scheduler(context);
            if (jobs == null) {
                AppPreferences.setSchedulerError(context,
                        "Android's background scheduler is unavailable.");
            } else {
                int nextJobId = previousJobId == SHORT_JOB_ID_A ? SHORT_JOB_ID_B : SHORT_JOB_ID_A;
                jobs.cancel(nextJobId);
                long delayMillis = ((long) refreshMinutes) * 60 * 1000;
                submitted = submit(context,
                        base(context, nextJobId, reason)
                                .setMinimumLatency(delayMillis)
                                .setOverrideDeadline(delayMillis + 300000)
                                .build());
            }
            return submitted;
        } catch (RuntimeException e) {
            return failed(context, e);
        }
    }

    public static int effectiveRefreshMinutes(Context context) {
        Context app = appContext(context);
        if (app == null || !AppPreferences.getAutomaticRefresh(app)) {
            return app == null ? 30 : AppPreferences.getRefreshMinutes(app);
        }
        long now = System.currentTimeMillis();
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return AdaptiveRefreshPolicy.chooseMinutes(
                AppPreferences.loadSnapshot(app),
                RefreshEngagement.score(app, now),
                hour,
                AppPreferences.getRefreshFailures(app),
                now);
    }

    public static boolean scheduleImmediate(Context context) {
        Context contextAppContext = appContext(context);
        if (contextAppContext == null) {
            return false;
        }
        if (!anySignedIn(contextAppContext)) {
            WidgetRenderer.updateAll(contextAppContext);
            return true;
        }
        try {
            DiagnosticLog.info(contextAppContext, "scheduler",
                    "immediate_refresh_requested");
            return submit(contextAppContext, base(contextAppContext, IMMEDIATE_JOB_ID, "immediate").setMinimumLatency(0L).setOverrideDeadline(5000L).build());
        } catch (RuntimeException e) {
            return failed(contextAppContext, e);
        }
    }

    /**
     * Fetches {@code provider}'s usage right away for a Refresh the user tapped outside the
     * app (the live monitor's button). Unlike {@link #scheduleImmediate}, which skips any
     * provider fetched in the last five minutes, this always asks the service, as the app's
     * own refresh does. It is an expedited job where Android has them, so it runs within
     * seconds with the app in the background or under battery restrictions, and it has no
     * network constraint, so with no connection it fails at once instead of waiting.
     */
    static boolean scheduleUserRefresh(Context context, UsageProvider provider) {
        if (provider == null) return false;
        return scheduleUserRefresh(context, java.util.Collections.singletonList(provider),
                USER_REFRESH_JOB_ID);
    }

    /**
     * A widget's Refresh button: fetches every provider the widget shows, however fresh its
     * reading is, the same way as {@link #scheduleUserRefresh(Context, UsageProvider)}.
     */
    static boolean scheduleWidgetRefresh(Context context, java.util.List<UsageProvider> providers) {
        return scheduleUserRefresh(context, providers, WIDGET_REFRESH_JOB_ID);
    }

    private static boolean scheduleUserRefresh(Context context,
            java.util.List<UsageProvider> providers, int jobId) {
        Context app = appContext(context);
        if (app == null || providers == null) return false;
        java.util.List<UsageProvider> targets = new java.util.ArrayList<>();
        for (UsageProvider provider : providers) {
            if (provider != null && !targets.contains(provider)
                    && (SecureTokenStore.isSignedIn(app, provider)
                            || DemoMode.isOn(app, provider))) {
                targets.add(provider);
            }
        }
        if (targets.isEmpty()) return scheduleImmediate(app);
        String ids = WidgetProviders.serialize(targets);
        DiagnosticLog.info(app, "scheduler", "user_refresh_requested", "provider", ids,
                "job_id", jobId);
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            try {
                if (submit(app, userRefreshJob(app, ids, jobId).setExpedited(true).build())) {
                    return true;
                }
            } catch (RuntimeException e) {
                // Out of expedited quota: fall through to a regular job.
                DiagnosticLog.warn(app, "scheduler", "user_refresh_expedited_refused",
                        "error", e.getClass().getSimpleName());
            }
        }
        try {
            return submit(app, userRefreshJob(app, ids, jobId)
                    .setMinimumLatency(0L).setOverrideDeadline(5000L).build());
        } catch (RuntimeException e) {
            return failed(app, e);
        }
    }

    /** Providers a user refresh job fetches, from its extras; an older single id still reads. */
    static java.util.List<UsageProvider> userRefreshTargets(PersistableBundle extras) {
        return WidgetProviders.parse(extras == null ? null : extras.getString(EXTRA_PROVIDER, ""));
    }

    private static JobInfo.Builder userRefreshJob(Context app, String providerIds, int jobId) {
        PersistableBundle extras = new PersistableBundle();
        extras.putString("reason", REASON_USER_REFRESH);
        extras.putString(EXTRA_PROVIDER, providerIds);
        return new JobInfo.Builder(jobId,
                new ComponentName(app, UsageRefreshJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                .setExtras(extras);
    }

    /**
     * Asks every provider again in {@link RefillGuard#CONFIRM_DELAY_MS}, to confirm or drop a held
     * refill. A check already waiting is kept, so repeated refreshes do not push it back.
     */
    static boolean scheduleRefillCheck(Context context) {
        Context app = appContext(context);
        if (app == null) return false;
        try {
            JobScheduler jobs = scheduler(app);
            if (jobs != null && jobs.getPendingJob(REFILL_CHECK_JOB_ID) != null) return true;
            long delay = RefillGuard.CONFIRM_DELAY_MS;
            return submit(app, base(app, REFILL_CHECK_JOB_ID, REASON_REFILL_CHECK)
                    .setMinimumLatency(delay).setOverrideDeadline(delay + 60_000L).build());
        } catch (RuntimeException e) {
            return failed(app, e);
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
            DiagnosticLog.info(contextAppContext, "scheduler",
                    "reset_refresh_requested",
                    "delay_ms", jMax);
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
                    jobSchedulerScheduler.cancel(REFILL_CHECK_JOB_ID);
                    jobSchedulerScheduler.cancel(USER_REFRESH_JOB_ID);
                    jobSchedulerScheduler.cancel(WIDGET_REFRESH_JOB_ID);
                    AppPreferences.setSchedulerError(contextAppContext, "");
                    DiagnosticLog.info(contextAppContext, "scheduler",
                            "all_refresh_jobs_cancelled");
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
        int result = jobSchedulerScheduler.schedule(jobInfo);
        String reason = jobInfo.getExtras() == null
                ? "" : jobInfo.getExtras().getString("reason", "");
        if (result == JobScheduler.RESULT_SUCCESS) {
            AppPreferences.setSchedulerError(context, "");
            DiagnosticLog.info(context, "scheduler", "job_scheduled",
                    "job_id", jobInfo.getId(),
                    "reason", reason);
            return true;
        }
        DiagnosticLog.warn(context, "scheduler", "job_rejected",
                "job_id", jobInfo.getId(),
                "reason", reason,
                "result", result);
        AppPreferences.setSchedulerError(context, "Android declined the background refresh request.");
        return false;
    }

    private static JobInfo.Builder base(Context context, int jobId, String reason) {
        PersistableBundle extras = new PersistableBundle();
        extras.putString("reason", reason);
        return new JobInfo.Builder(jobId, new ComponentName(context, (Class<?>) UsageRefreshJobService.class)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setExtras(extras);
    }

    private static JobScheduler scheduler(Context context) {
        return (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
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
        DiagnosticLog.error(context, "scheduler", "scheduler_failed", runtimeException);
        return false;
    }
}
