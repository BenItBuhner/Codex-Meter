package dev.bennett.codexmeter;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;

/** Bridges PackageInstaller status callbacks to the user-confirmation activity. */
public final class UpdateInstallReceiver extends BroadcastReceiver {
    public static final String EXTRA_VERSION = "update_version";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !AppConstants.ACTION_INSTALL_STATUS.equals(intent.getAction())) {
            return;
        }
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirmation;
            if (Build.VERSION.SDK_INT >= 33) {
                confirmation = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
            } else {
                @SuppressWarnings("deprecation")
                Intent legacy = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                confirmation = legacy;
            }
            if (confirmation == null) {
                UpdatePreferences.setInstallError(context,
                        "Android did not provide an update confirmation screen.");
                return;
            }
            try {
                confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(confirmation);
            } catch (RuntimeException exception) {
                UpdatePreferences.setInstallError(context,
                        "Could not open Android's update confirmation screen.");
            }
            return;
        }
        if (status == PackageInstaller.STATUS_SUCCESS) {
            UpdatePreferences.setInstallError(context, "");
            return;
        }
        String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        if (message == null || message.trim().isEmpty()) {
            message = "Android rejected the update (status " + status + ").";
        }
        UpdatePreferences.setInstallError(context, message);
    }
}
