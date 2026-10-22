package dev.bennett.codexmeter;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.android.gms.wearable.DataClient;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.Wearable;
import dev.bennett.codexmeter.wear.WearProvidersState;
import dev.bennett.codexmeter.wear.WearSettingsState;
import dev.bennett.codexmeter.wear.WearSyncPaths;
import dev.bennett.codexmeter.wear.WearSyncStatus;

public final class WearMainActivity extends Activity implements DataClient.OnDataChangedListener {
    private static final int REQUEST_NOTIFICATIONS = 8714;
    private TextView accountValue;
    private TextView creditsValue;
    private TextView fiveHourValue;
    private TextView primaryLabel;
    private ImageView providerIcon;
    private TextView providersHeader;
    private TextView providersHint;
    private LinearLayout providersList;
    private TextView title;
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
        primaryLabel = findViewById(R.id.primary_label);
        providerIcon = findViewById(R.id.provider_icon);
        providersHeader = findViewById(R.id.providers_header);
        providersHint = findViewById(R.id.providers_hint);
        providersList = findViewById(R.id.providers_list);
        title = findViewById(R.id.title);
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
        long now = System.currentTimeMillis();
        String providerId = WearPreferences.selectedProviderId(this);
        String providerName = WearPreferences.selectedProviderName(this);
        WatchMeters.Meter first = WatchMeters.primary(snapshot, now);
        WatchMeters.Meter second = WatchMeters.secondary(snapshot, now);
        title.setText(providerName);
        providerIcon.setImageResource(WearProviderIcons.forId(providerId));
        primaryLabel.setText(first == null ? getString(R.string.wear_five_hour) : first.label);
        fiveHourValue.setText(WearGlanceFormat.remainingPercentText(WatchMeters.window(first)));
        if (second != null) {
            weeklyValue.setText(getString(R.string.wear_long_window_value,
                    second.shortLabel, WearGlanceFormat.remainingPercentText(second.window)));
            weeklyValue.setVisibility(View.VISIBLE);
        } else if (first == null) {
            weeklyValue.setText(getString(R.string.wear_long_window_value,
                    WearGlanceFormat.longWindowShortLabel(snapshot), "--"));
            weeklyValue.setVisibility(View.VISIBLE);
        } else {
            weeklyValue.setVisibility(View.GONE);
        }
        accountValue.setText(accountText(snapshot, providerName));
        String details = WearGlanceFormat.resetCreditsText(snapshot);
        WearSettingsState settings = WearPreferences.settingsState(this, 0L,
                WearSettingsState.SOURCE_WEAR);
        String pace = WearGlanceFormat.paceWarning(snapshot,
                settings.usagePaceEnabled, settings.usagePaceSensitivity,
                now);
        if (!pace.isEmpty()) {
            details = details.isEmpty() ? pace : details + " · " + pace;
        }
        creditsValue.setText(details);
        creditsValue.setVisibility(details.isEmpty() ? View.GONE : View.VISIBLE);
        toggleMonitorButton.setText(WearOngoingMonitor.isActive(this)
                ? R.string.wear_stop_monitor : R.string.wear_start_monitor);
        statusValue.setText(statusText());
        renderProviders(providerId, now);
    }

    /** Plan badge for the followed provider; "Claude Code usage" when it reports no plan. */
    private String accountText(UsageSnapshot snapshot, String providerName) {
        if (snapshot != null && snapshot.allowed && !snapshot.limitReached
                && snapshot.planType.trim().isEmpty()) {
            return getString(R.string.wear_provider_usage, providerName);
        }
        return WearGlanceFormat.accountStatus(snapshot);
    }

    /**
     * Every provider the phone shows, so the watch can switch between them. Hidden when the
     * phone has only one (or is an older build that sends Codex alone).
     */
    private void renderProviders(String selectedId, long now) {
        WearProvidersState state = WearPreferences.providersState(this);
        boolean show = state != null && state.providers.size() > 1;
        providersHeader.setVisibility(show ? View.VISIBLE : View.GONE);
        providersHint.setVisibility(show ? View.VISIBLE : View.GONE);
        providersList.setVisibility(show ? View.VISIBLE : View.GONE);
        providersList.removeAllViews();
        if (!show) return;
        LayoutInflater inflater = LayoutInflater.from(this);
        for (WearProvidersState.Entry entry : state.providers) {
            View row = inflater.inflate(R.layout.wear_provider_row, providersList, false);
            ((ImageView) row.findViewById(R.id.provider_row_icon))
                    .setImageResource(WearProviderIcons.forId(entry.id));
            ((TextView) row.findViewById(R.id.provider_row_name)).setText(entry.displayName);
            UsageSnapshot usage = WearPreferences.CODEX_ID.equals(entry.id)
                    && WearPreferences.loadCodexSnapshot(this) != null
                    ? WearPreferences.loadCodexSnapshot(this) : entry.snapshot;
            String summary = !entry.signedIn ? getString(R.string.wear_provider_signed_out)
                    : usage == null ? getString(R.string.wear_provider_waiting)
                    : WatchMeters.pairLongText(usage, now);
            boolean selected = entry.id.equals(selectedId);
            ((TextView) row.findViewById(R.id.provider_row_summary)).setText(selected
                    ? getString(R.string.wear_provider_following, summary) : summary);
            row.setSelected(selected);
            row.setContentDescription(entry.displayName + ", " + summary);
            row.setOnClickListener(view -> {
                WearPreferences.setWatchProvider(this, entry.id);
                WearOngoingMonitor.updateFromSnapshot(this, WearPreferences.loadSnapshot(this));
                refreshUi();
            });
            providersList.addView(row);
        }
    }

    private String statusText() {
        if (!WearPreferences.isConnected(this)) {
            return getString(R.string.wear_waiting_phone);
        }
        WearSyncStatus status = WearPreferences.syncStatus(this);
        if (!WearPreferences.isSelectedSignedIn(this)) {
            return getString(R.string.wear_sign_in_phone);
        }
        if (status.refreshInProgress) return getString(R.string.wear_refreshing);
        String error = WearPreferences.selectedError(this);
        if (!error.isEmpty()) {
            return getString(R.string.wear_refresh_failed, error);
        }
        if (!WearPreferences.hasSyncedUsage(this)) {
            return getString(R.string.wear_waiting_sync);
        }
        if (WearPreferences.isMonitorDesired(this)
                && !WearOngoingMonitor.canPostNotifications(this)) {
            return getString(R.string.wear_enable_notifications);
        }
        long last = WearPreferences.CODEX_ID.equals(WearPreferences.selectedProviderId(this))
                ? Math.max(WearPreferences.lastUsageAt(this), status.lastSuccessAtMillis)
                : WearPreferences.lastUsageAt(this);
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
