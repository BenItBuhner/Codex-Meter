package dev.bennett.codexmeter;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.format.DateUtils;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import com.google.android.gms.wearable.DataClient;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.Wearable;
import dev.bennett.codexmeter.wear.WearSettingsState;
import dev.bennett.codexmeter.wear.WearSyncPaths;
import dev.bennett.codexmeter.wear.WearSyncStatus;

public final class WearMainActivity extends Activity implements DataClient.OnDataChangedListener {
    private static final int REQUEST_NOTIFICATIONS = 8714;
    private TextView accountValue;
    private TextView creditsValue;
    private TextView fiveHourValue;
    private TextView statusValue;
    private Button toggleMonitorButton;
    private TextView weeklyValue;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wear_main);
        accountValue = findViewById(R.id.account_value);
        creditsValue = findViewById(R.id.credits_value);
        fiveHourValue = findViewById(R.id.five_hour_value);
        weeklyValue = findViewById(R.id.weekly_value);
        statusValue = findViewById(R.id.status_value);
        toggleMonitorButton = findViewById(R.id.toggle_monitor_button);
        findViewById(R.id.refresh_button).setOnClickListener(view -> {
            WearPhoneSync.sendMessageToPhone(this, WearSyncPaths.MSG_REFRESH);
            statusValue.setText(R.string.wear_refresh_requested);
        });
        toggleMonitorButton.setOnClickListener(view -> toggleMonitor());
        findViewById(R.id.settings_button)
                .setOnClickListener(view -> startActivity(new Intent(this,
                        WearSettingsActivity.class)));
        refreshUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshUi();
        WearPhoneSync.syncFromPhone(this, this::refreshUi);
        Wearable.getNodeClient(this).getConnectedNodes()
                .addOnSuccessListener(nodes -> {
                    WearPreferences.markConnected(this, nodes != null && !nodes.isEmpty());
                    refreshUi();
                });
    }

    @Override
    protected void onStart() {
        super.onStart();
        Wearable.getDataClient(this).addListener(this);
    }

    @Override
    protected void onStop() {
        Wearable.getDataClient(this).removeListener(this);
        super.onStop();
    }

    @Override
    public void onDataChanged(DataEventBuffer dataEvents) {
        for (DataEvent event : dataEvents) {
            if (event.getType() == DataEvent.TYPE_CHANGED && event.getDataItem() != null) {
                WearPhoneSync.applyDataItem(this, event.getDataItem());
            }
        }
        refreshUi();
    }

    private void toggleMonitor() {
        if (WearOngoingMonitor.isActive(this)) {
            WearOngoingMonitor.stop(this, true);
        } else {
            if (!WearOngoingMonitor.canPostNotifications(this)) {
                requestNotificationPermission();
                return;
            }
            startMonitor();
        }
        refreshUi();
    }

    private void startMonitor() {
        WearPreferences.setMonitorActive(this, true);
        WearPhoneSync.pushSettings(this);
        WearPhoneSync.sendMessageToPhone(this, WearSyncPaths.MSG_START_MONITOR);
        if (!WearOngoingMonitor.start(this)) {
            statusValue.setText(R.string.wear_monitor_waiting);
        }
    }

    private void refreshUi() {
        UsageSnapshot snapshot = WearPreferences.loadSnapshot(this);
        UsageWindow fiveHour = WearGlanceFormat.currentFiveHour(snapshot);
        UsageWindow weekly = WearGlanceFormat.currentWeekly(snapshot);
        fiveHourValue.setText(WearGlanceFormat.remainingPercentText(fiveHour));
        weeklyValue.setText(getString(R.string.wear_week_value,
                WearGlanceFormat.remainingPercentText(weekly)));
        accountValue.setText(WearGlanceFormat.accountStatus(snapshot));
        String details = WearGlanceFormat.resetCreditsText(snapshot);
        WearSettingsState settings = WearPreferences.settingsState(this, 0L,
                WearSettingsState.SOURCE_WEAR);
        String pace = WearGlanceFormat.paceWarning(snapshot,
                settings.usagePaceEnabled, settings.usagePaceSensitivity,
                System.currentTimeMillis());
        if (!pace.isEmpty()) {
            details = details.isEmpty() ? pace : details + " · " + pace;
        }
        creditsValue.setText(details);
        creditsValue.setVisibility(details.isEmpty() ? View.GONE : View.VISIBLE);
        toggleMonitorButton.setText(WearOngoingMonitor.isActive(this)
                ? R.string.wear_stop_monitor : R.string.wear_start_monitor);
        statusValue.setText(statusText());
    }

    private String statusText() {
        if (!WearPreferences.isConnected(this)) {
            return getString(R.string.wear_waiting_phone);
        }
        WearSyncStatus status = WearPreferences.syncStatus(this);
        if (status.updatedAtMillis > 0L && !status.signedIn) {
            return getString(R.string.wear_sign_in_phone);
        }
        if (status.refreshInProgress) return getString(R.string.wear_refreshing);
        if (!status.lastError.isEmpty()) {
            return getString(R.string.wear_refresh_failed, status.lastError);
        }
        if (!WearPreferences.hasSyncedUsage(this)) {
            return getString(R.string.wear_waiting_sync);
        }
        if (WearPreferences.isMonitorDesired(this)
                && !WearOngoingMonitor.canPostNotifications(this)) {
            return getString(R.string.wear_enable_notifications);
        }
        long last = Math.max(WearPreferences.lastUsageAt(this), status.lastSuccessAtMillis);
        String ago = last <= 0L ? "synced" : DateUtils.getRelativeTimeSpanString(
                last, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString();
        WearSettingsState settings = WearPreferences.settingsState(this, 0L,
                WearSettingsState.SOURCE_WEAR);
        if (WearGlanceFormat.isStale(last, settings.refreshMinutes,
                System.currentTimeMillis())) {
            return getString(R.string.wear_stale_updated, ago);
        }
        return getString(R.string.wear_phone_updated, ago);
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_NOTIFICATIONS);
        } else {
            startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_NOTIFICATIONS) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startMonitor();
        }
        refreshUi();
    }
}
