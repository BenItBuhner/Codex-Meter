package dev.bennett.codexmeter;

import android.app.job.JobParameters;
import android.app.job.JobService;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

/** Executes reset-watch polling without keeping an Activity alive. */
public final class ResetWatchJobService extends JobService {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Map<JobParameters, Future<?>> active = new HashMap<>();

    @Override
    public boolean onStartJob(JobParameters params) {
        if (!AnnouncementPreferences.monitoringEnabled(this)
                || !SecureXTokenStore.isConnected(this)) {
            return false;
        }
        FutureTask<Void> task = new FutureTask<>(() -> {
            try {
                ResetWatchClient.check(getApplicationContext());
            } catch (Exception ignored) {
                // The persisted status explains the failure; the next cadence retries.
            } finally {
                boolean shouldFinish;
                synchronized (active) {
                    shouldFinish = active.remove(params) != null;
                }
                if (shouldFinish) jobFinished(params, false);
            }
        }, null);
        synchronized (active) {
            active.put(params, task);
        }
        executor.execute(task);
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        Future<?> task;
        synchronized (active) {
            task = active.remove(params);
        }
        if (task != null) task.cancel(true);
        return AnnouncementPreferences.monitoringEnabled(this)
                && SecureXTokenStore.isConnected(this);
    }

    @Override
    public void onDestroy() {
        synchronized (active) {
            for (Future<?> task : active.values()) task.cancel(true);
            active.clear();
        }
        executor.shutdownNow();
        super.onDestroy();
    }
}
