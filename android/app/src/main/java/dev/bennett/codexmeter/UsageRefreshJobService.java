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
        private final boolean shortCycle;
        private volatile boolean stopped;
        private FutureTask<Void> task;

        JobRun(JobParameters jobParameters) {
            this.params = jobParameters;
            this.shortCycle = "short_periodic".equals(jobParameters.getExtras() == null ? "" : jobParameters.getExtras().getString("reason", ""));
        }

        @Override // java.lang.Runnable
        public void run() {
            try {
                try {
                    RefreshScheduler.scheduleAtNextReset(UsageRefreshJobService.this.getApplicationContext(), UsageApi.refreshAndCache(UsageRefreshJobService.this.getApplicationContext()));
                    WidgetRenderer.updateAll(UsageRefreshJobService.this.getApplicationContext());
                    UsageRefreshJobService.this.active.remove(Integer.valueOf(this.params.getJobId()), this);
                    if (!this.stopped) {
                        UsageRefreshJobService usageRefreshJobService = UsageRefreshJobService.this;
                        JobParameters jobParameters = this.params;
                        if (this.shortCycle) {
                        }
                        usageRefreshJobService.jobFinished(jobParameters, false);
                        if (this.shortCycle && SecureTokenStore.isSignedIn(UsageRefreshJobService.this.getApplicationContext())) {
                            RefreshScheduler.scheduleNextShort(UsageRefreshJobService.this.getApplicationContext(), this.params.getJobId());
                        }
                    }
                } catch (Exception e) {
                    AppPreferences.setLastError(UsageRefreshJobService.this.getApplicationContext(), UsageRefreshJobService.safeMessage(e));
                    WidgetRenderer.updateAll(UsageRefreshJobService.this.getApplicationContext());
                    UsageRefreshJobService.this.active.remove(Integer.valueOf(this.params.getJobId()), this);
                    if (!this.stopped) {
                        UsageRefreshJobService.this.jobFinished(this.params, !this.shortCycle);
                        if (this.shortCycle && SecureTokenStore.isSignedIn(UsageRefreshJobService.this.getApplicationContext())) {
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
                    if (this.shortCycle) {
                    }
                    usageRefreshJobService2.jobFinished(jobParameters2, false);
                    if (this.shortCycle && SecureTokenStore.isSignedIn(UsageRefreshJobService.this.getApplicationContext())) {
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
