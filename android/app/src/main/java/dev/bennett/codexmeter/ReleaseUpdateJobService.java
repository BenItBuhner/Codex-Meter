package dev.bennett.codexmeter;

import android.app.job.JobParameters;
import android.app.job.JobService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Executes persisted release checks without keeping an Activity alive. */
public final class ReleaseUpdateJobService extends JobService {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile Future<?> active;
    private volatile JobParameters activeParameters;

    @Override
    public boolean onStartJob(JobParameters params) {
        if (!UpdatePreferences.automaticChecks(this)) {
            return false;
        }
        activeParameters = params;
        active = executor.submit(() -> {
            boolean retry = false;
            try {
                ReleaseUpdateClient.check(getApplicationContext());
            } catch (Exception exception) {
                retry = true;
            } finally {
                if (activeParameters == params) {
                    activeParameters = null;
                    active = null;
                    jobFinished(params, retry);
                }
            }
        });
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        Future<?> task = active;
        if (activeParameters == params) {
            activeParameters = null;
            active = null;
        }
        if (task != null) {
            task.cancel(true);
        }
        return UpdatePreferences.automaticChecks(this);
    }

    @Override
    public void onDestroy() {
        Future<?> task = active;
        if (task != null) {
            task.cancel(true);
        }
        executor.shutdownNow();
        super.onDestroy();
    }
}
