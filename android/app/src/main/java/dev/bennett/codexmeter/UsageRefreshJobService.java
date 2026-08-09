package dev.bennett.codexmeter;

import android.app.job.JobParameters;
import android.app.job.JobService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;

/* JADX INFO: loaded from: classes.dex */
public final class UsageRefreshJobService extends JobService {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ConcurrentMap<Integer, JobRun> active = new ConcurrentHashMap();

    @Override // android.app.job.JobService
    public boolean onStartJob(final JobParameters jobParameters) {
        if (!SecureTokenStore.isSignedIn(this)) {
            DiagnosticLog.info(this, "scheduler", "refresh_job_skipped_signed_out",
                    "job_id", jobParameters.getJobId());
            WidgetRenderer.updateAll(this);
            return false;
        }
        final JobRun jobRun = new JobRun(jobParameters);
        FutureTask<Void> futureTask = new FutureTask<Void>(jobRun, null) { // from class: dev.bennett.codexmeter.UsageRefreshJobService.1
            @Override // java.util.concurrent.FutureTask
            protected void done() {
                if (isCancelled()) {
                    UsageRefreshJobService.this.active.remove(Integer.valueOf(jobParameters.getJobId()), jobRun);
                }
            }
        };
        jobRun.task = futureTask;
        JobRun jobRunPut = this.active.put(Integer.valueOf(jobParameters.getJobId()), jobRun);
        if (jobRunPut != null) {
            jobRunPut.stopped = true;
            jobRunPut.task.cancel(true);
        }
        this.executor.execute(futureTask);
        return true;
    }

    @Override // android.app.job.JobService
    public boolean onStopJob(JobParameters jobParameters) {
        DiagnosticLog.warn(this, "scheduler", "refresh_job_stopped",
                "job_id", jobParameters.getJobId());
        JobRun jobRunRemove = this.active.remove(Integer.valueOf(jobParameters.getJobId()));
        if (jobRunRemove != null) {
            jobRunRemove.stopped = true;
            jobRunRemove.task.cancel(true);
        }
        return true;
    }

    @Override // android.app.Service
    public void onDestroy() {
        for (JobRun jobRun : this.active.values()) {
            jobRun.stopped = true;
            jobRun.task.cancel(true);
        }
        this.active.clear();
        this.executor.shutdownNow();
        super.onDestroy();
    }

    private final class JobRun implements Runnable {
        private final JobParameters params;
        private final boolean chainedCycle;
        private final String reason;
        private volatile boolean stopped;
        private FutureTask<Void> task;

        JobRun(JobParameters jobParameters) {
            this.params = jobParameters;
            this.reason = jobParameters.getExtras() == null
                    ? "" : jobParameters.getExtras().getString("reason", "");
            this.chainedCycle = RefreshScheduler.REASON_SHORT_PERIODIC.equals(this.reason)
                    || RefreshScheduler.REASON_ADAPTIVE.equals(this.reason);
        }

        @Override // java.lang.Runnable
        public void run() {
            long started = android.os.SystemClock.elapsedRealtime();
            DiagnosticLog.info(UsageRefreshJobService.this, "scheduler",
                    "refresh_job_started",
                    "job_id", this.params.getJobId(),
                    "reason", this.reason);
            try {
                try {
                    RefreshScheduler.scheduleAtNextReset(UsageRefreshJobService.this.getApplicationContext(), UsageApi.refreshAndCache(UsageRefreshJobService.this.getApplicationContext()));
                    AppPreferences.recordRefreshSuccess(
                            UsageRefreshJobService.this.getApplicationContext());
                    WidgetRenderer.updateAll(UsageRefreshJobService.this.getApplicationContext());
                    DiagnosticLog.info(UsageRefreshJobService.this, "scheduler",
                            "refresh_job_succeeded",
                            "job_id", this.params.getJobId(),
                            "reason", this.reason,
                            "duration_ms", android.os.SystemClock.elapsedRealtime() - started);
                    UsageRefreshJobService.this.active.remove(Integer.valueOf(this.params.getJobId()), this);
                    if (!this.stopped) {
                        UsageRefreshJobService usageRefreshJobService = UsageRefreshJobService.this;
                        JobParameters jobParameters = this.params;
                        usageRefreshJobService.jobFinished(jobParameters, false);
                        if (this.chainedCycle && SecureTokenStore.isSignedIn(UsageRefreshJobService.this.getApplicationContext())) {
                            RefreshScheduler.scheduleNextShort(UsageRefreshJobService.this.getApplicationContext(), this.params.getJobId());
                        }
                    }
                } catch (Exception e) {
                    DiagnosticLog.error(UsageRefreshJobService.this, "scheduler",
                            "refresh_job_failed", e,
                            "job_id", this.params.getJobId(),
                            "reason", this.reason,
                            "duration_ms", android.os.SystemClock.elapsedRealtime() - started);
                    AppPreferences.setLastError(UsageRefreshJobService.this.getApplicationContext(), UsageRefreshJobService.safeMessage(e));
                    AppPreferences.recordRefreshFailure(
                            UsageRefreshJobService.this.getApplicationContext());
                    WidgetRenderer.updateAll(UsageRefreshJobService.this.getApplicationContext());
                    UsageRefreshJobService.this.active.remove(Integer.valueOf(this.params.getJobId()), this);
                    if (!this.stopped) {
                        UsageRefreshJobService.this.jobFinished(this.params, !this.chainedCycle);
                        if (this.chainedCycle && SecureTokenStore.isSignedIn(UsageRefreshJobService.this.getApplicationContext())) {
                            RefreshScheduler.scheduleNextShort(UsageRefreshJobService.this.getApplicationContext(), this.params.getJobId());
                        }
                    }
                }
            } catch (Throwable th) {
                WidgetRenderer.updateAll(UsageRefreshJobService.this.getApplicationContext());
                UsageRefreshJobService.this.active.remove(Integer.valueOf(this.params.getJobId()), this);
                if (!this.stopped) {
                    UsageRefreshJobService usageRefreshJobService2 = UsageRefreshJobService.this;
                    JobParameters jobParameters2 = this.params;
                    usageRefreshJobService2.jobFinished(jobParameters2, false);
                    if (this.chainedCycle && SecureTokenStore.isSignedIn(UsageRefreshJobService.this.getApplicationContext())) {
                        RefreshScheduler.scheduleNextShort(UsageRefreshJobService.this.getApplicationContext(), this.params.getJobId());
                    }
                }
                throw th;
            }
        }
    }

    public static String safeMessage(Exception exc) {
        String message = exc.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return "Usage refresh failed.";
        }
        return message.length() > 240 ? message.substring(0, 240) : message;
    }
}
