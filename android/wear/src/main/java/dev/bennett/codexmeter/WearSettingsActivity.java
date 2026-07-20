package dev.bennett.codexmeter;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import dev.bennett.codexmeter.wear.WearSettingsState;
import dev.bennett.codexmeter.wear.WearSurfaceMode;
import dev.bennett.codexmeter.wear.WearSyncPaths;

public final class WearSettingsActivity extends Activity {
    private static final int REQUEST_NOTIFICATIONS = 8715;
    private Switch acceleratedStartSwitch;
    private Switch autoStartSwitch;
    private Spinner displayModeSpinner;
    private TextView displayModeSummary;
    private String[] displayModeValues;
    private boolean initializing;
    private Spinner metricSpinner;
    private String[] metricValues;
    private Switch monitorSwitch;
    private Spinner paceSensitivitySpinner;
    private String[] paceSensitivityValues;
    private Spinner percentModeSpinner;
    private String[] percentModeValues;
    private Spinner thresholdSpinner;
    private String[] thresholdValues;
    private Switch usagePaceSwitch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wear_settings);
        displayModeValues = getResources().getStringArray(R.array.wear_display_modes);
        percentModeValues = getResources().getStringArray(R.array.wear_percent_modes);
        metricValues = getResources().getStringArray(R.array.wear_metrics);
        thresholdValues = getResources().getStringArray(R.array.wear_thresholds);
        paceSensitivityValues = getResources()
                .getStringArray(R.array.wear_pace_sensitivities);
        displayModeSpinner = findViewById(R.id.display_mode_spinner);
        percentModeSpinner = findViewById(R.id.percent_mode_spinner);
        metricSpinner = findViewById(R.id.metric_spinner);
        thresholdSpinner = findViewById(R.id.threshold_spinner);
        paceSensitivitySpinner = findViewById(R.id.pace_sensitivity_spinner);
        autoStartSwitch = findViewById(R.id.auto_start_switch);
        usagePaceSwitch = findViewById(R.id.usage_pace_switch);
        acceleratedStartSwitch = findViewById(R.id.accelerated_start_switch);
        monitorSwitch = findViewById(R.id.monitor_switch);
        displayModeSummary = findViewById(R.id.display_mode_summary);
        bindSpinner(displayModeSpinner, R.array.wear_display_mode_labels);
        bindSpinner(percentModeSpinner, R.array.wear_percent_mode_labels);
        bindSpinner(metricSpinner, R.array.wear_metric_labels);
        bindSpinner(thresholdSpinner, R.array.wear_thresholds);
        bindSpinner(paceSensitivitySpinner, R.array.wear_pace_sensitivity_labels);
        loadState();
        setupListeners();
        Button back = findViewById(R.id.back_button);
        back.setOnClickListener(view -> finish());
    }

    private void loadState() {
        initializing = true;
        WearSettingsState state = WearPreferences.settingsState(this, 0L,
                WearSettingsState.SOURCE_WEAR);
        displayModeSpinner.setSelection(indexOf(displayModeValues, state.displayMode));
        percentModeSpinner.setSelection(indexOf(percentModeValues, state.percentMode));
        metricSpinner.setSelection(indexOf(metricValues, state.metric));
        thresholdSpinner.setSelection(indexOf(thresholdValues, Integer.toString(state.threshold)));
        paceSensitivitySpinner.setSelection(indexOf(paceSensitivityValues,
                state.usagePaceSensitivity));
        autoStartSwitch.setChecked(state.autoStartEnabled);
        usagePaceSwitch.setChecked(state.usagePaceEnabled);
        acceleratedStartSwitch.setChecked(state.acceleratedStartEnabled);
        monitorSwitch.setChecked(state.monitorActive);
        initializing = false;
        updateDisplaySummary();
    }

    private void setupListeners() {
        android.widget.AdapterView.OnItemSelectedListener listener =
                new android.widget.AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(android.widget.AdapterView<?> parent,
                            android.view.View view, int position, long id) {
                        if (!initializing) saveCurrent(false);
                        updateDisplaySummary();
                    }

                    @Override
                    public void onNothingSelected(android.widget.AdapterView<?> parent) {
                    }
                };
        displayModeSpinner.setOnItemSelectedListener(listener);
        percentModeSpinner.setOnItemSelectedListener(listener);
        metricSpinner.setOnItemSelectedListener(listener);
        thresholdSpinner.setOnItemSelectedListener(listener);
        paceSensitivitySpinner.setOnItemSelectedListener(listener);
        CompoundButton.OnCheckedChangeListener checkedListener =
                (buttonView, isChecked) -> {
                    if (!initializing) {
                        if (buttonView == monitorSwitch && isChecked
                                && !WearOngoingMonitor.canPostNotifications(this)) {
                            initializing = true;
                            monitorSwitch.setChecked(false);
                            initializing = false;
                            requestNotificationPermission();
                            return;
                        }
                        saveCurrent(buttonView == monitorSwitch);
                    }
                };
        autoStartSwitch.setOnCheckedChangeListener(checkedListener);
        usagePaceSwitch.setOnCheckedChangeListener(checkedListener);
        acceleratedStartSwitch.setOnCheckedChangeListener(checkedListener);
        monitorSwitch.setOnCheckedChangeListener(checkedListener);
    }

    private void saveCurrent(boolean monitorChanged) {
        // Preserve whatever refresh interval is cached locally. The phone owns the schedule:
        // PhoneWearSync deliberately ignores Wear refresh_minutes so a fresh-watch default
        // cannot clobber 5/10/15/etc. after the first Wear settings edit.
        WearSettingsState existing = WearPreferences.settingsState(this, 0L,
                WearSettingsState.SOURCE_WEAR);
        WearSettingsState state = new WearSettingsState(
                selected(displayModeValues, displayModeSpinner),
                selected(percentModeValues, percentModeSpinner),
                autoStartSwitch.isChecked(),
                selected(metricValues, metricSpinner),
                Integer.parseInt(selected(thresholdValues, thresholdSpinner)),
                monitorSwitch.isChecked(),
                existing.refreshMinutes,
                System.currentTimeMillis(),
                WearSettingsState.SOURCE_WEAR,
                getPackageName(),
                usagePaceSwitch.isChecked(),
                selected(paceSensitivityValues, paceSensitivitySpinner),
                acceleratedStartSwitch.isChecked());
        WearPreferences.saveLocalSettings(this, state);
        WearPhoneSync.pushSettings(this);
        if (monitorChanged) {
            if (state.monitorActive) {
                WearPreferences.setMonitorActive(this, true);
                WearOngoingMonitor.start(this);
                WearPhoneSync.sendMessageToPhone(this, WearSyncPaths.MSG_START_MONITOR);
            } else {
                WearOngoingMonitor.stop(this, true);
            }
        } else if (state.monitorActive) {
            WearOngoingMonitor.restore(this);
        }
    }

    private void updateDisplaySummary() {
        String selected = selected(displayModeValues, displayModeSpinner);
        WearSurfaceMode resolved = WearSurfaceMode.resolve(selected, android.os.Build.VERSION.SDK_INT,
                WearOngoingMonitor.canUseLocalLiveUpdates(this));
        String summary;
        if (resolved == WearSurfaceMode.LIVE_UPDATE) {
            summary = getString(R.string.wear_surface_live);
        } else if (NowBarDisplayMode.SAMSUNG_COMPATIBILITY.equals(selected)) {
            summary = getString(R.string.wear_surface_ongoing);
        } else {
            summary = getString(R.string.wear_surface_auto);
        }
        displayModeSummary.setText(summary);
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
        if (requestCode == REQUEST_NOTIFICATIONS && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            monitorSwitch.setChecked(true);
        }
    }

    private void bindSpinner(Spinner spinner, int labelsRes) {
        ArrayAdapter<CharSequence> adapter = new ArrayAdapter<>(this,
                R.layout.wear_spinner_item, android.R.id.text1,
                getResources().getTextArray(labelsRes)) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                return styleSpinnerText(super.getView(position, convertView, parent));
            }

            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                return styleSpinnerText(super.getDropDownView(position, convertView, parent));
            }
        };
        adapter.setDropDownViewResource(R.layout.wear_spinner_dropdown_item);
        spinner.setAdapter(adapter);
    }

    private static View styleSpinnerText(View view) {
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            text.setTextColor(0xFFFFFFFF);
            text.setAlpha(1f);
            text.setSingleLine(true);
            text.setTextSize(16f);
        }
        return view;
    }

    private static int indexOf(String[] values, String value) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(value)) return i;
        }
        return 0;
    }

    private static String selected(String[] values, Spinner spinner) {
        int position = spinner.getSelectedItemPosition();
        if (position < 0 || position >= values.length) return values[0];
        return values[position];
    }
}
