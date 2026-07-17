package dev.bennett.codexmeter;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.widget.Button;
import android.widget.TextView;
import com.google.android.gms.wearable.Wearable;
import dev.bennett.codexmeter.wear.WearSyncPaths;

public final class WearMainActivity extends Activity {
    private TextView fiveHourValue;
    private TextView statusValue;
    private Button toggleMonitorButton;
    private TextView weeklyValue;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wear_main);
        fiveHourValue = findViewById(R.id.five_hour_value);
        weeklyValue = findViewById(R.id.weekly_value);
        statusValue = findViewById(R.id.status_value);
        toggleMonitorButton = findViewById(R.id.toggle_monitor_button);
        findViewById(R.id.refresh_button).setOnClickListener(view -> {
            WearPhoneSync.sendMessageToPhone(this, WearSyncPaths.MSG_REFRESH);
            statusValue.setText("Refresh requested");
        });
        toggleMonitorButton.setOnClickListener(view -> toggleMonitor());
        findViewById(R.id.demo_button).setOnClickListener(view -> {
            WearPreferences.seedDemoSnapshot(this);
            refreshUi();
            WearOngoingMonitor.restore(this);
        });
        findViewById(R.id.settings_button)
                .setOnClickListener(view -> startActivity(new Intent(this,
                        WearSettingsActivity.class)));
        requestNotificationPermission();
        Intent launch = getIntent();
        if (launch != null && launch.getBooleanExtra("demo", false)) {
            WearPreferences.seedDemoSnapshot(this);
            if (launch.getBooleanExtra("start_monitor", false)) {
                WearOngoingMonitor.start(this);
            }
        }
        refreshUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshUi();
        Wearable.getNodeClient(this).getConnectedNodes()
                .addOnSuccessListener(nodes -> {
                    WearPreferences.markConnected(this, nodes != null && !nodes.isEmpty());
                    refreshUi();
                });
    }

    private void toggleMonitor() {
        if (WearOngoingMonitor.isActive(this)) {
            WearOngoingMonitor.stop(this, true);
        } else {
            WearPreferences.setMonitorActive(this, true);
            WearPhoneSync.pushSettings(this);
            WearPhoneSync.sendMessageToPhone(this, WearSyncPaths.MSG_START_MONITOR);
            if (!WearOngoingMonitor.start(this)) {
                statusValue.setText("Start requested; waiting for phone usage");
            }
        }
        refreshUi();
    }

    private void refreshUi() {
        UsageSnapshot snapshot = WearPreferences.loadSnapshot(this);
        UsageWindow fiveHour = snapshot == null ? null
                : UsageSnapshot.currentWindow(snapshot.fiveHour, System.currentTimeMillis());
        UsageWindow weekly = snapshot == null ? null
                : UsageSnapshot.currentWindow(snapshot.weekly, System.currentTimeMillis());
        fiveHourValue.setText("5h " + remainingText(fiveHour));
        weeklyValue.setText("Weekly " + remainingText(weekly));
        toggleMonitorButton.setText(WearOngoingMonitor.isActive(this)
                ? "Stop monitor" : "Start monitor");
        statusValue.setText(statusText());
    }

    private String statusText() {
        if (!WearPreferences.isConnected(this)) {
            return "Waiting for phone";
        }
        if (!WearPreferences.hasSyncedUsage(this)) {
            return "Phone paired · waiting for sync";
        }
        long last = WearPreferences.lastUsageAt(this);
        String ago = last <= 0L ? "synced" : DateUtils.getRelativeTimeSpanString(
                last, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString();
        return "Phone paired · " + ago;
    }

    private static String remainingText(UsageWindow window) {
        return window == null ? "--" : window.remainingPercent() + "%";
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 8714);
        }
    }
}
