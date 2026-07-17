package dev.bennett.codexmeter;

import android.app.Activity;
import android.os.Bundle;
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
    private Switch autoStartSwitch;
    private Spinner displayModeSpinner;
    private TextView displayModeSummary;
    private String[] displayModeValues;
    private boolean initializing;
    private Spinner metricSpinner;
    private String[] metricValues;
    private Switch monitorSwitch;
    private Spinner percentModeSpinner;
    private String[] percentModeValues;
    private Spinner thresholdSpinner;
    private String[] thresholdValues;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wear_settings);
        displayModeValues = getResources().getStringArray(R.array.wear_display_modes);
        percentModeValues = getResources().getStringArray(R.array.wear_percent_modes);
        metricValues = getResources().getStringArray(R.array.wear_metrics);
        thresholdValues = getResources().getStringArray(R.array.wear_thresholds);
        displayModeSpinner = findViewById(R.id.display_mode_spinner);
        percentModeSpinner = findViewById(R.id.percent_mode_spinner);
        metricSpinner = findViewById(R.id.metric_spinner);
        thresholdSpinner = findViewById(R.id.threshold_spinner);
        autoStartSwitch = findViewById(R.id.auto_start_switch);
        monitorSwitch = findViewById(R.id.monitor_switch);
        displayModeSummary = findViewById(R.id.display_mode_summary);
        bindSpinner(displayModeSpinner, R.array.wear_display_mode_labels);
        bindSpinner(percentModeSpinner, R.array.wear_percent_mode_labels);
        bindSpinner(metricSpinner, R.array.wear_metric_labels);
        bindSpinner(thresholdSpinner, R.array.wear_thresholds);
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
        autoStartSwitch.setChecked(state.autoStartEnabled);
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
        CompoundButton.OnCheckedChangeListener checkedListener =
                (buttonView, isChecked) -> {
                    if (!initializing) {
                        saveCurrent(buttonView == monitorSwitch);
                    }
                };
        autoStartSwitch.setOnCheckedChangeListener(checkedListener);
        monitorSwitch.setOnCheckedChangeListener(checkedListener);
    }

    private void saveCurrent(boolean monitorChanged) {
        // Preserve the phone-synced refresh interval; Wear has no UI to change it, so a
        // hardcoded 30 would clobber whatever interval the phone already uses.
        int refreshMinutes = WearPreferences.settingsState(this, 0L,
                WearSettingsState.SOURCE_WEAR).refreshMinutes;
        WearSettingsState state = new WearSettingsState(
                selected(displayModeValues, displayModeSpinner),
                selected(percentModeValues, percentModeSpinner),
                autoStartSwitch.isChecked(),
                selected(metricValues, metricSpinner),
                Integer.parseInt(selected(thresholdValues, thresholdSpinner)),
                monitorSwitch.isChecked(),
                refreshMinutes,
                System.currentTimeMillis(),
                WearSettingsState.SOURCE_WEAR);
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
            summary = "Wear OS 7+ maps this to local Live Updates when allowed; Ongoing Activity still powers the watch chip and Recents.";
        } else if (NowBarDisplayMode.SAMSUNG_COMPATIBILITY.equals(selected)) {
            summary = "Samsung phone Now Bar extras do not exist on Wear. Use Tiles, Complications, and Ongoing Activity here.";
        } else {
            summary = "Auto picks the best Wear-native surface: Ongoing Activity now, local Live Update when supported.";
        }
        displayModeSummary.setText(summary);
    }

    private void bindSpinner(Spinner spinner, int labelsRes) {
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this, labelsRes,
                android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
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
