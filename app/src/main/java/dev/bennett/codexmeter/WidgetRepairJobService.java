package dev.bennett.codexmeter;

import android.app.job.JobParameters;
import android.app.job.JobService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Performs post-upgrade widget bitmap and PendingIntent repair outside receiver/UI threads. */
public final class WidgetRepairJobService extends JobService {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile Future<?> active;
    private volatile JobParameters parameters;

    @Override
    public boolean onStartJob(JobParameters params) {
        parameters = params;
        active = executor.submit(() -> {
            try {
                WidgetUpgradeRepair.perform(getApplicationContext());
            } finally {
                if (parameters == params) {
                    parameters = null;
                    active = null;
                    jobFinished(params, false);
                }
            }
        });
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        Future<?> task = active;
        if (parameters == params) {
            parameters = null;
            active = null;
        }
        if (task != null) {
            task.cancel(true);
        }
        return true;
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
