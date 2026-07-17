package dev.bennett.codexmeter;

import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.text.format.DateUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreferenceCompat;
import dev.bennett.codexmeter.wear.PhoneWearSync;
import dev.oneuiproject.oneui.layout.ToolbarLayout;
import dev.oneuiproject.oneui.preference.HorizontalRadioPreference;
import dev.oneuiproject.oneui.preference.LayoutPreference;
import dev.oneuiproject.oneui.widget.RoundedLinearLayout;
import dev.oneuiproject.oneui.widget.CardItemView;
import java.math.BigDecimal;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Settings built from the One UI Design Library preference components used by its sample app. */
public final class SettingsActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle bundle) {
        Ui.applySelectedTheme(this);
        super.onCreate(bundle);
        AppPreferences.setAppStyle(this, WidgetOptions.SURFACE_ONE_UI);
        setContentView(R.layout.activity_settings);
        ToolbarLayout toolbar = findViewById(R.id.settings_toolbar_layout);
        Ui.configureReachToolbar(toolbar, "Settings", true);
        if (bundle == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.settings_fragment, new SettingsFragment())
                    .commit();
        }
    }

    public static final class SettingsFragment extends PreferenceFragmentCompat {
        private Preference expiryTimesPreference;
        private Preference permissionPreference;
        private Preference testNotificationPreference;
        private SwitchPreferenceCompat nowBarMonitorPreference;
        private SwitchPreferenceCompat nowBarAutoStartPreference;
        private ListPreference nowBarDisplayModePreference;
        private ListPreference nowBarPercentModePreference;
        private ListPreference nowBarMetricPreference;
        private ListPreference nowBarThresholdPreference;
        private Preference nowBarPermissionPreference;
        private Preference updateStatusPreference;
        private Preference resetWatchAccountPreference;
        private Preference resetWatchStatusPreference;
        private SwitchPreferenceCompat resetWatchMonitorPreference;

        @Override
        public void onCreatePreferences(Bundle bundle, String rootKey) {
            getPreferenceManager().setSharedPreferencesName("codex_meter_settings_v1");
            addPreferencesFromResource(R.xml.preferences_settings);
            bindAccount();
            bindAppearance();
            bindRefresh();
            bindResetWatch();
            bindUpdates();
            bindNotifications();
            bindNowBar();
            findPreference("about_codex_meter").setOnPreferenceClickListener(preference -> {
                Ui.startSecondaryActivity(requireActivity(), AboutActivity.class);
                return true;
            });
        }

        @Override
        public void onViewCreated(View view, Bundle bundle) {
            super.onViewCreated(view, bundle);
            view.setBackgroundColor(Ui.background(requireContext(), Ui.isDark(requireContext())));
        }

        @Override
        public void onResume() {
            super.onResume();
            updatePermissionSummary();
            updateNowBarSummary();
            updateResetWatchSummary();
            updateUpdateSummary();
        }

        private void bindAccount() {
            boolean dark = Ui.isDark(requireContext());
            LayoutPreference preference = findPreference("account_card");
            RoundedLinearLayout card = preference.findViewById(R.id.settings_account_card);
            card.setBackground(Ui.card(requireContext(), dark).getBackground());
            ImageView avatar = preference.findViewById(R.id.settings_account_avatar);
            GradientDrawable avatarBackground = new GradientDrawable();
            avatarBackground.setShape(GradientDrawable.OVAL);
            avatarBackground.setColor(Ui.controlSurface(requireContext(), dark));
            avatar.setBackground(avatarBackground);
            avatar.setPadding(Ui.dp(requireContext(), 10), Ui.dp(requireContext(), 10),
                    Ui.dp(requireContext(), 10), Ui.dp(requireContext(), 10));
            avatar.setImageResource(R.drawable.ic_oui_contact_outline);
            avatar.setColorFilter(Ui.mainText(dark));
            TextView title = preference.findViewById(R.id.settings_account_title);
            TextView summary = preference.findViewById(R.id.settings_account_summary);
            TextView plan = preference.findViewById(R.id.settings_account_plan);
            CardItemView action = preference.findViewById(R.id.settings_account_action);
            title.setTextColor(Ui.mainText(dark));
            summary.setTextColor(Ui.secondaryText(dark));
            plan.setTextColor(Ui.mainText(dark));
            plan.setBackground(Ui.pillBackground(requireContext(), dark));

            AuthTokens tokens = SecureTokenStore.load(requireContext());
            UsageSnapshot snapshot = AppPreferences.loadSnapshot(requireContext());
            title.setText(tokens == null ? "Not connected" : "ChatGPT account");
            summary.setText(tokens == null ? "Sign in from the dashboard"
                    : (tokens.email.isEmpty() ? "Connected" : tokens.email));
            if (tokens != null && snapshot != null) {
                String label = UsageFormat.planLabel(snapshot.planType);
                plan.setText(label.isEmpty() ? "Codex" : label);
                plan.setVisibility(View.VISIBLE);
            } else {
                plan.setVisibility(View.GONE);
            }
            action.getTitleView().setText(tokens == null ? "Sign in with ChatGPT" : "Sign out");
            action.getTitleView().setTextColor(tokens == null
                    ? Ui.accent(requireContext(), dark)
                    : (dark ? 0xFFFF6B6B : 0xFFFF3B30));
            action.setOnClickListener(view -> {
                if (SecureTokenStore.isSignedIn(requireContext())) {
                    confirmSignOut();
                } else {
                    startActivity(new Intent(requireContext(), MainActivity.class)
                            .putExtra("start_sign_in", true));
                    requireActivity().finish();
                }
            });
        }

        private void confirmSignOut() {
            androidx.appcompat.app.AlertDialog dialog = new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle("Sign out?")
                    .setMessage("This removes encrypted ChatGPT tokens and cached usage from this device.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Sign out", (dialogInterface, which) -> {
                        AuthTokens tokens = SecureTokenStore.load(requireContext());
                        SecureTokenStore.clear(requireContext());
                        AppPreferences.clearSnapshot(requireContext());
                        AppPreferences.setOAuthPending(requireContext(), false, "");
                        RefreshScheduler.cancelAll(requireContext());
                        ResetAlertScheduler.cancelAll(requireContext());
                        WidgetRenderer.updateAll(requireContext());
                        Toast.makeText(requireContext(), "Signed out.", Toast.LENGTH_SHORT).show();
                        requireActivity().recreate();
                        if (tokens != null) {
                            new Thread(() -> OAuthClient.revokeBestEffort(tokens), "codex-sign-out").start();
                        }
                    })
                    .create();
            dialog.show();
        }

        private void bindAppearance() {
            String selected = AppPreferences.getAppTheme(requireContext());
            boolean useSystem = WidgetOptions.THEME_SYSTEM.equals(selected);
            HorizontalRadioPreference theme = findPreference("app_theme");
            SwitchPreferenceCompat system = findPreference("theme_system_ui");
            system.setEnabled(true);
            // The visual radio shows the effective light/dark mode while AppPreferences also
            // stores the third "system" state under app_theme. Do not let setValue() overwrite
            // that system state with the currently resolved light/dark entry.
            theme.setPersistent(false);
            theme.setDividerEnabled(false);
            theme.setTouchEffectEnabled(false);
            theme.setValue(useSystem
                    ? (Ui.isDark(requireContext()) ? WidgetOptions.THEME_DARK : WidgetOptions.THEME_LIGHT)
                    : selected);
            theme.setEnabled(!useSystem);
            system.setChecked(useSystem);
            theme.setOnPreferenceChangeListener((preference, value) -> {
                AppPreferences.setAppTheme(requireContext(), String.valueOf(value));
                requireActivity().recreate();
                return true;
            });
            system.setOnPreferenceChangeListener((preference, value) -> {
                boolean enabled = (Boolean) value;
                AppPreferences.setAppTheme(requireContext(), enabled
                        ? WidgetOptions.THEME_SYSTEM
                        : (Ui.isDark(requireContext()) ? WidgetOptions.THEME_DARK : WidgetOptions.THEME_LIGHT));
                requireActivity().recreate();
                return true;
            });
        }

        private void bindRefresh() {
            SwitchPreferenceCompat onLaunch = findPreference("refresh_on_launch");
            onLaunch.setEnabled(true);
            onLaunch.setChecked(AppPreferences.getRefreshOnLaunch(requireContext()));
            onLaunch.setOnPreferenceChangeListener((preference, value) -> {
                AppPreferences.setRefreshOnLaunch(requireContext(), (Boolean) value);
                return true;
            });

            ListPreference interval = findPreference("refresh_interval_ui");
            interval.setValue(String.valueOf(AppPreferences.getRefreshMinutes(requireContext())));
            interval.setOnPreferenceChangeListener((preference, value) -> {
                AppPreferences.setRefreshMinutes(requireContext(), Integer.parseInt(String.valueOf(value)));
                RefreshScheduler.schedulePeriodic(requireContext());
                PhoneWearSync.pushSettings(requireContext());
                return true;
            });
        }

        private void bindResetWatch() {
            resetWatchAccountPreference = findPreference("reset_watch_account");
            resetWatchAccountPreference.setOnPreferenceClickListener(preference -> {
                if (SecureXTokenStore.isConnected(requireContext())) {
                    new AlertDialog.Builder(requireContext())
                            .setTitle("Disconnect X?")
                            .setMessage("This removes encrypted X tokens and cached reset-watch "
                                    + "posts from this device.")
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Disconnect", (dialog, which) -> {
                                XAuthentication.disconnect(requireContext());
                                Toast.makeText(requireContext(), "X disconnected.",
                                        Toast.LENGTH_SHORT).show();
                                updateResetWatchSummary();
                            })
                            .show();
                } else {
                    startXConnection();
                }
                return true;
            });

            resetWatchMonitorPreference = findPreference("reset_watch_monitor_ui");
            resetWatchMonitorPreference.setPersistent(false);
            resetWatchMonitorPreference.setChecked(
                    AnnouncementPreferences.monitoringEnabled(requireContext()));
            resetWatchMonitorPreference.setOnPreferenceChangeListener((preference, value) -> {
                boolean enabled = (Boolean) value;
                if (enabled && !SecureXTokenStore.isConnected(requireContext())) {
                    startXConnection();
                    return false;
                }
                AnnouncementPreferences.setMonitoringEnabled(requireContext(), enabled);
                if (enabled) {
                    ResetWatchScheduler.ensureScheduled(requireContext());
                    ResetWatchScheduler.requestImmediate(requireContext());
                }
                updateResetWatchSummary();
                return true;
            });

            ListPreference interval = findPreference("reset_watch_interval_ui");
            interval.setPersistent(false);
            interval.setValue(String.valueOf(
                    AnnouncementPreferences.intervalMinutes(requireContext())));
            interval.setOnPreferenceChangeListener((preference, value) -> {
                AnnouncementPreferences.setIntervalMinutes(requireContext(),
                        Integer.parseInt(String.valueOf(value)));
                ResetWatchScheduler.ensureScheduled(requireContext());
                updateResetWatchSummary();
                return true;
            });

            SwitchPreferenceCompat notifications =
                    findPreference("reset_watch_notifications_ui");
            notifications.setPersistent(false);
            notifications.setChecked(
                    AnnouncementPreferences.notificationsEnabled(requireContext()));
            notifications.setOnPreferenceChangeListener((preference, value) -> {
                boolean enabled = (Boolean) value;
                AnnouncementPreferences.setNotificationsEnabled(requireContext(), enabled);
                if (enabled) {
                    ResetWatchNotificationManager.ensureChannel(requireContext());
                    if (Build.VERSION.SDK_INT >= 33
                            && requireContext().checkSelfPermission(
                            "android.permission.POST_NOTIFICATIONS")
                            != PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(
                                new String[]{"android.permission.POST_NOTIFICATIONS"}, 8603);
                    }
                }
                return true;
            });

            SwitchPreferenceCompat banner = findPreference("reset_watch_banner_ui");
            banner.setPersistent(false);
            banner.setChecked(AnnouncementPreferences.bannerEnabled(requireContext()));
            banner.setOnPreferenceChangeListener((preference, value) -> {
                AnnouncementPreferences.setBannerEnabled(requireContext(), (Boolean) value);
                return true;
            });

            findPreference("reset_watch_check_now").setOnPreferenceClickListener(preference -> {
                if (!SecureXTokenStore.isConnected(requireContext())) {
                    startXConnection();
                    return true;
                }
                boolean scheduled = ResetWatchScheduler.requestImmediate(requireContext());
                Toast.makeText(requireContext(), scheduled
                                ? "Reset-watch check scheduled."
                                : "Could not schedule the reset-watch check.",
                        scheduled ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
                return true;
            });
            resetWatchStatusPreference = findPreference("reset_watch_status");
            updateResetWatchSummary();
        }

        private void startXConnection() {
            try {
                XAuthentication.begin(requireActivity());
            } catch (Exception exception) {
                Toast.makeText(requireContext(), MainActivity.safeMessage(exception),
                        Toast.LENGTH_LONG).show();
            }
        }

        private void updateResetWatchSummary() {
            if (resetWatchStatusPreference == null || getContext() == null) return;
            boolean configured = XAuthentication.isConfigured();
            boolean connected = SecureXTokenStore.isConnected(requireContext());
            if (resetWatchAccountPreference != null) {
                resetWatchAccountPreference.setTitle(connected ? "Disconnect X" : "Connect X");
                resetWatchAccountPreference.setSummary(configured
                        ? (connected ? "Connected with read-only OAuth access"
                        : "Read @thsottiaux through the official X API")
                        : "Unavailable: this build has no X Native App client ID");
            }
            if (resetWatchMonitorPreference != null) {
                resetWatchMonitorPreference.setChecked(
                        AnnouncementPreferences.monitoringEnabled(requireContext()));
            }
            StringBuilder summary = new StringBuilder();
            if (!configured) {
                summary.append("X OAuth is not configured for this build");
            } else if (!connected) {
                summary.append("Not connected");
            } else if (AnnouncementPreferences.monitoringEnabled(requireContext())) {
                summary.append("Monitoring every ")
                        .append(AnnouncementPreferences.intervalMinutes(requireContext()))
                        .append(" minutes (best effort)");
            } else {
                summary.append("Connected · automatic checks off");
            }
            long checkedAt = AnnouncementPreferences.lastCheckMillis(requireContext());
            if (checkedAt > 0L) {
                summary.append(" · Checked ").append(DateUtils.getRelativeTimeSpanString(
                        checkedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
                        DateUtils.FORMAT_ABBREV_RELATIVE));
            }
            String error = AnnouncementPreferences.lastError(requireContext());
            if (!error.isEmpty()) summary.append(" · ").append(error);
            resetWatchStatusPreference.setSummary(summary.toString());
        }

        private void bindUpdates() {
            SwitchPreferenceCompat automatic = findPreference("automatic_update_checks_ui");
            automatic.setPersistent(false);
            automatic.setChecked(UpdatePreferences.automaticChecks(requireContext()));
            automatic.setOnPreferenceChangeListener((preference, value) -> {
                boolean enabled = (Boolean) value;
                UpdatePreferences.setAutomaticChecks(requireContext(), enabled);
                if (enabled) {
                    ReleaseUpdateScheduler.ensureScheduled(requireContext());
                } else {
                    ReleaseUpdateScheduler.cancel(requireContext());
                }
                return true;
            });
            updateStatusPreference = findPreference("update_status");
            findPreference("check_for_updates").setOnPreferenceClickListener(preference -> {
                startActivity(new Intent(requireContext(), UpdateActivity.class)
                        .putExtra(UpdateActivity.EXTRA_FORCE_CHECK, true));
                return true;
            });
            findPreference("release_history").setOnPreferenceClickListener(preference -> {
                Ui.startSecondaryActivity(requireActivity(), ReleaseHistoryActivity.class);
                return true;
            });
            updateUpdateSummary();
        }

        private void updateUpdateSummary() {
            if (updateStatusPreference == null || getContext() == null) {
                return;
            }
            GitHubRelease available = UpdatePreferences.availableUpdate(requireContext());
            GitHubRelease latest = UpdatePreferences.latestStable(requireContext());
            long checkedAt = UpdatePreferences.lastCheckMillis(requireContext());
            StringBuilder summary = new StringBuilder("v")
                    .append(UpdatePreferences.installedVersion(requireContext()));
            if (available != null) {
                summary.append(" installed · v").append(available.version).append(" available");
            } else if (checkedAt == 0L) {
                summary.append(" · Not checked yet");
            } else if (latest == null) {
                summary.append(" · No published releases");
            } else {
                summary.append(" · Up to date");
            }
            if (checkedAt > 0L) {
                summary.append(" · Checked ").append(DateUtils.getRelativeTimeSpanString(
                        checkedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
                        DateUtils.FORMAT_ABBREV_RELATIVE));
            }
            String error = UpdatePreferences.lastError(requireContext());
            if (!error.isEmpty()) {
                summary.append(" · ").append(error);
            }
            updateStatusPreference.setSummary(summary.toString());
        }

        private void bindNotifications() {
            SwitchPreferenceCompat allow = findPreference("notifications_allowed_ui");
            allow.setEnabled(true);
            allow.setChecked(ResetAlertPreferences.enabled(requireContext()));
            allow.setOnPreferenceChangeListener((preference, value) -> {
                setNotificationsEnabled((Boolean) value);
                return true;
            });

            ListPreference metric = findPreference("notification_metric_ui");
            metric.setValue(ResetAlertPreferences.getMetric(requireContext()));
            metric.setOnPreferenceChangeListener((preference, value) -> {
                saveAlert(ResetAlertPreferences.getStyle(requireContext()), String.valueOf(value),
                        ResetAlertPreferences.getThreshold(requireContext()));
                return true;
            });

            ListPreference threshold = findPreference("notification_threshold_ui");
            threshold.setValue(String.valueOf(ResetAlertPreferences.getThreshold(requireContext())));
            threshold.setOnPreferenceChangeListener((preference, value) -> {
                saveAlert(ResetAlertPreferences.getStyle(requireContext()),
                        ResetAlertPreferences.getMetric(requireContext()), Integer.parseInt(String.valueOf(value)));
                return true;
            });

            SwitchPreferenceCompat unexpectedRefills = findPreference("unexpected_refills_ui");
            unexpectedRefills.setPersistent(false);
            unexpectedRefills.setChecked(ResetAlertPreferences.unexpectedRefillsEnabled(requireContext()));
            unexpectedRefills.setOnPreferenceChangeListener((preference, value) -> {
                ResetAlertPreferences.setUnexpectedRefillsEnabled(requireContext(), (Boolean) value);
                return true;
            });

            SwitchPreferenceCompat resetCreditIncreases = findPreference("reset_credit_increases_ui");
            resetCreditIncreases.setPersistent(false);
            resetCreditIncreases.setChecked(ResetAlertPreferences.resetCreditIncreasesEnabled(requireContext()));
            resetCreditIncreases.setOnPreferenceChangeListener((preference, value) -> {
                ResetAlertPreferences.setResetCreditIncreasesEnabled(requireContext(), (Boolean) value);
                return true;
            });

            SwitchPreferenceCompat resetCreditExpiry =
                    findPreference("reset_credit_expiry_ui");
            resetCreditExpiry.setPersistent(false);
            resetCreditExpiry.setChecked(
                    ResetAlertPreferences.resetCreditExpiryEnabled(requireContext()));
            resetCreditExpiry.setOnPreferenceChangeListener((preference, value) -> {
                boolean enabled = (Boolean) value;
                ResetAlertPreferences.setResetCreditExpiryEnabled(requireContext(), enabled);
                expiryTimesPreference.setEnabled(enabled);
                scheduleResetCreditExpiryReminders();
                return true;
            });

            expiryTimesPreference = findPreference("reset_credit_expiry_times_ui");
            expiryTimesPreference.setEnabled(
                    ResetAlertPreferences.resetCreditExpiryEnabled(requireContext()));
            expiryTimesPreference.setOnPreferenceClickListener(preference -> {
                showExpiryReminderTimesDialog();
                return true;
            });
            updateExpiryTimesSummary();

            permissionPreference = findPreference("notification_permission");
            permissionPreference.setOnPreferenceClickListener(preference -> {
                startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().getPackageName()));
                return true;
            });
            testNotificationPreference = findPreference("notification_test");
            testNotificationPreference.setOnPreferenceClickListener(preference -> {
                boolean sent = ResetNotificationManager.sendTestNotification(requireContext());
                Toast.makeText(requireContext(), sent
                        ? "Test notification sent."
                        : "Enable notifications and allow permission first.",
                        sent ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
                return true;
            });
            updatePermissionSummary();
        }

        private void showExpiryReminderTimesDialog() {
            List<Long> leadTimes = ResetAlertPreferences.getResetCreditExpiryLeadTimes(
                    requireContext());
            AlertDialog.Builder builder = new AlertDialog.Builder(requireContext())
                    .setTitle("Reminder times")
                    .setNeutralButton("Add", (dialog, which) ->
                            showAddExpiryReminderDialog())
                    .setNegativeButton("Done", null);
            if (leadTimes.isEmpty()) {
                builder.setMessage("No reminder times are configured. Add one to choose how "
                        + "long before expiry Codex Meter should notify you.");
            } else {
                String[] labels = new String[leadTimes.size()];
                for (int i = 0; i < leadTimes.size(); i++) {
                    labels[i] = formatLeadTime(leadTimes.get(i))
                            + " before expiry — tap to remove";
                }
                builder.setItems(labels, (dialog, which) -> {
                    List<Long> updated = new ArrayList<>(leadTimes);
                    long removed = updated.remove(which);
                    saveExpiryLeadTimes(updated);
                    Toast.makeText(requireContext(),
                            formatLeadTime(removed) + " reminder removed.",
                            Toast.LENGTH_SHORT).show();
                });
            }
            builder.show();
        }

        private void showAddExpiryReminderDialog() {
            boolean dark = Ui.isDark(requireContext());
            LinearLayout container = new LinearLayout(requireContext());
            container.setOrientation(LinearLayout.VERTICAL);
            container.setPadding(Ui.dp(requireContext(), 24), Ui.dp(requireContext(), 8),
                    Ui.dp(requireContext(), 24), 0);
            TextView explanation = Ui.text(requireContext(),
                    "Notify me this long before each available reset credit expires.",
                    14.0f, Ui.secondaryText(dark));
            container.addView(explanation, new LinearLayout.LayoutParams(-1, -2));

            LinearLayout inputRow = Ui.horizontal(requireContext(), 12);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
            rowParams.setMargins(0, Ui.dp(requireContext(), 16), 0, 0);
            EditText amount = new EditText(requireContext());
            amount.setHint("Amount");
            amount.setSingleLine(true);
            amount.setTextColor(Ui.mainText(dark));
            amount.setHintTextColor(Ui.secondaryText(dark));
            amount.setInputType(InputType.TYPE_CLASS_NUMBER
                    | InputType.TYPE_NUMBER_FLAG_DECIMAL);
            inputRow.addView(amount, new LinearLayout.LayoutParams(0,
                    Ui.dp(requireContext(), 54), 1.0f));
            String[] units = {"Minutes", "Hours", "Days", "Weeks"};
            Spinner unit = Ui.spinner(requireContext(), units, dark);
            unit.setSelection(1);
            LinearLayout.LayoutParams unitParams = new LinearLayout.LayoutParams(
                    Ui.dp(requireContext(), 142), Ui.dp(requireContext(), 54));
            unitParams.setMargins(Ui.dp(requireContext(), 8), 0, 0, 0);
            inputRow.addView(unit, unitParams);
            container.addView(inputRow, rowParams);

            AlertDialog dialog = new AlertDialog.Builder(requireContext())
                    .setTitle("Add reminder time")
                    .setView(container)
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Add", null)
                    .create();
            dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    .setOnClickListener(view -> {
                        Long leadTime = parseLeadTime(amount.getText().toString(),
                                unit.getSelectedItemPosition());
                        if (leadTime == null) {
                            amount.setError("Enter a time from 1 minute to 1 year, "
                                    + "in whole minutes.");
                            return;
                        }
                        List<Long> updated = new ArrayList<>(
                                ResetAlertPreferences.getResetCreditExpiryLeadTimes(
                                        requireContext()));
                        if (!updated.contains(leadTime)) updated.add(leadTime);
                        saveExpiryLeadTimes(updated);
                        dialog.dismiss();
                    }));
            dialog.show();
        }

        private Long parseLeadTime(String amount, int unitPosition) {
            long[] unitMillis = {
                    TimeUnit.MINUTES.toMillis(1),
                    TimeUnit.HOURS.toMillis(1),
                    TimeUnit.DAYS.toMillis(1),
                    TimeUnit.DAYS.toMillis(7)
            };
            if (amount == null || amount.trim().isEmpty()
                    || unitPosition < 0 || unitPosition >= unitMillis.length) {
                return null;
            }
            try {
                char decimalSeparator = DecimalFormatSymbols.getInstance()
                        .getDecimalSeparator();
                String normalized = decimalSeparator == '.'
                        ? amount.trim() : amount.trim().replace(decimalSeparator, '.');
                long value = new BigDecimal(normalized)
                        .multiply(BigDecimal.valueOf(unitMillis[unitPosition]))
                        .longValueExact();
                return value >= ResetCreditExpiryReminder.MIN_LEAD_TIME_MS
                        && value <= ResetCreditExpiryReminder.MAX_LEAD_TIME_MS
                        && value % ResetCreditExpiryReminder.MIN_LEAD_TIME_MS == 0L
                        ? value : null;
            } catch (ArithmeticException | NumberFormatException exception) {
                return null;
            }
        }

        private void saveExpiryLeadTimes(List<Long> leadTimes) {
            ResetAlertPreferences.setResetCreditExpiryLeadTimes(requireContext(), leadTimes);
            updateExpiryTimesSummary();
            scheduleResetCreditExpiryReminders();
        }

        private void updateExpiryTimesSummary() {
            if (expiryTimesPreference == null) return;
            List<Long> leadTimes = ResetAlertPreferences.getResetCreditExpiryLeadTimes(
                    requireContext());
            if (leadTimes.isEmpty()) {
                expiryTimesPreference.setSummary("No reminder times configured");
                return;
            }
            List<String> labels = new ArrayList<>();
            for (Long leadTime : leadTimes) labels.add(formatLeadTime(leadTime));
            expiryTimesPreference.setSummary(String.join(", ", labels) + " before expiry");
        }

        private String formatLeadTime(long millis) {
            if (millis % TimeUnit.DAYS.toMillis(7) == 0L) {
                long weeks = millis / TimeUnit.DAYS.toMillis(7);
                return weeks + " week" + (weeks == 1 ? "" : "s");
            }
            if (millis % TimeUnit.DAYS.toMillis(1) == 0L) {
                long days = millis / TimeUnit.DAYS.toMillis(1);
                return days + " day" + (days == 1 ? "" : "s");
            }
            if (millis % TimeUnit.HOURS.toMillis(1) == 0L) {
                long hours = millis / TimeUnit.HOURS.toMillis(1);
                return hours + " hour" + (hours == 1 ? "" : "s");
            }
            long minutes = millis / TimeUnit.MINUTES.toMillis(1);
            return minutes + " minute" + (minutes == 1 ? "" : "s");
        }

        private void scheduleResetCreditExpiryReminders() {
            ResetNotificationManager.onResetCreditExpirySettingsChanged(requireContext(),
                    AppPreferences.loadResetCredits(requireContext()));
        }

        private void bindNowBar() {
            nowBarDisplayModePreference = findPreference("now_bar_display_mode_ui");
            nowBarDisplayModePreference.setPersistent(false);
            nowBarDisplayModePreference.setValue(
                    NowBarPreferences.getDisplayMode(requireContext()));
            nowBarDisplayModePreference.setOnPreferenceChangeListener((preference, value) -> {
                String mode = NowBarDisplayMode.normalize(String.valueOf(value));
                NowBarPreferences.setDisplayMode(requireContext(), mode);
                nowBarDisplayModePreference.setValue(mode);
                if (NowBarManager.isActive(requireContext())
                        && !NowBarManager.repostActive(requireContext())) {
                    Toast.makeText(requireContext(),
                            "Could not refresh this display mode, so the monitor was stopped.",
                            Toast.LENGTH_LONG).show();
                }
                updateNowBarSummary();
                PhoneWearSync.pushSettings(requireContext());
                return true;
            });

            nowBarPercentModePreference = findPreference("now_bar_percent_mode_ui");
            nowBarPercentModePreference.setPersistent(false);
            nowBarPercentModePreference.setValue(
                    NowBarPreferences.getPercentMode(requireContext()));
            nowBarPercentModePreference.setOnPreferenceChangeListener((preference, value) -> {
                String mode = NowBarPercentMode.normalize(String.valueOf(value));
                NowBarPreferences.setPercentMode(requireContext(), mode);
                nowBarPercentModePreference.setValue(mode);
                if (NowBarManager.isActive(requireContext())
                        && !NowBarManager.applyPercentModeChange(requireContext())) {
                    Toast.makeText(requireContext(),
                            "Could not refresh the percentage mode, so the monitor was stopped.",
                            Toast.LENGTH_LONG).show();
                }
                updateNowBarSummary();
                PhoneWearSync.pushSettings(requireContext());
                return true;
            });

            nowBarMonitorPreference = findPreference("now_bar_monitor_ui");
            nowBarMonitorPreference.setPersistent(false);
            nowBarMonitorPreference.setOnPreferenceChangeListener((preference, value) -> {
                boolean enabled = (Boolean) value;
                if (!enabled) {
                    NowBarManager.stop(requireContext(), true);
                    updateNowBarSummary();
                    PhoneWearSync.pushSettings(requireContext());
                    return true;
                }
                if (!ensureNotificationPermission()) return false;
                boolean started = NowBarManager.start(requireContext());
                if (!started) {
                    Toast.makeText(requireContext(),
                            "Refresh your signed-in usage before starting the monitor.",
                            Toast.LENGTH_LONG).show();
                }
                updateNowBarSummary();
                View settingsView = getView();
                if (started && settingsView != null) {
                    settingsView.postDelayed(this::updateNowBarSummary, 1500L);
                }
                PhoneWearSync.pushSettings(requireContext());
                return started;
            });

            nowBarAutoStartPreference = findPreference("now_bar_auto_start_ui");
            nowBarAutoStartPreference.setPersistent(false);
            nowBarAutoStartPreference.setChecked(
                    NowBarPreferences.isAutoStartEnabled(requireContext()));
            nowBarAutoStartPreference.setOnPreferenceChangeListener((preference, value) -> {
                boolean enabled = (Boolean) value;
                if (enabled && !ensureNotificationPermission()) return false;
                saveNowBarAutoStart(enabled, NowBarPreferences.getMetric(requireContext()),
                        NowBarPreferences.getThreshold(requireContext()));
                return true;
            });

            nowBarMetricPreference = findPreference("now_bar_metric_ui");
            nowBarMetricPreference.setPersistent(false);
            nowBarMetricPreference.setValue(NowBarPreferences.getMetric(requireContext()));
            nowBarMetricPreference.setOnPreferenceChangeListener((preference, value) -> {
                saveNowBarAutoStart(NowBarPreferences.isAutoStartEnabled(requireContext()),
                        String.valueOf(value),
                        NowBarPreferences.getThreshold(requireContext()));
                return true;
            });

            nowBarThresholdPreference = findPreference("now_bar_threshold_ui");
            nowBarThresholdPreference.setPersistent(false);
            nowBarThresholdPreference.setValue(
                    String.valueOf(NowBarPreferences.getThreshold(requireContext())));
            nowBarThresholdPreference.setOnPreferenceChangeListener((preference, value) -> {
                saveNowBarAutoStart(NowBarPreferences.isAutoStartEnabled(requireContext()),
                        NowBarPreferences.getMetric(requireContext()),
                        Integer.parseInt(String.valueOf(value)));
                return true;
            });

            Preference preview = findPreference("now_bar_preview");
            preview.setVisible((requireContext().getApplicationInfo().flags
                    & ApplicationInfo.FLAG_DEBUGGABLE) != 0);
            preview.setOnPreferenceClickListener(preference -> {
                if (!ensureNotificationPermission()) return true;
                boolean started = NowBarManager.startPreview(requireContext());
                Toast.makeText(requireContext(), started
                        ? "Sample Live Update started for 20 minutes."
                        : "Allow notifications first.",
                        started ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
                updateNowBarSummary();
                return true;
            });

            nowBarPermissionPreference = findPreference("now_bar_permission");
            nowBarPermissionPreference.setOnPreferenceClickListener(preference -> {
                if (!NowBarManager.canPostNotifications(requireContext())) {
                    startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE,
                                    requireContext().getPackageName()));
                    return true;
                }
                // AUTO deliberately keeps this screen reachable: a false promotion result can
                // mean either user-disabled access or an OEM policy denial. The API cannot
                // distinguish them, and routing by the Samsung fallback would prevent users
                // from enabling Android Live Updates here.
                if (Build.VERSION.SDK_INT >= 36
                        && !NowBarDisplayMode.SAMSUNG_COMPATIBILITY.equals(
                        NowBarPreferences.getDisplayMode(requireContext()))) {
                    Intent promotion = new Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().getPackageName());
                    try {
                        startActivity(promotion);
                        return true;
                    } catch (RuntimeException ignored) {
                    }
                }
                startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().getPackageName()));
                return true;
            });
            findPreference("now_bar_setup_help").setOnPreferenceClickListener(preference -> {
                showSamsungNowBarHelp();
                return true;
            });
            updateNowBarAutoStartEnabledState();
            updateNowBarSummary();
        }

        private void showSamsungNowBarHelp() {
            new AlertDialog.Builder(requireContext())
                    .setTitle("Samsung Now Bar setup")
                    .setMessage("For Android Live Updates:\n"
                            + "1. Open Settings > About phone/tablet > Software information.\n"
                            + "2. Tap Build number seven times and confirm your screen lock.\n"
                            + "3. Return to Settings > Developer options.\n"
                            + "4. Turn on Live notifications for all apps.\n"
                            + "5. Make sure Settings > Lock screen and AOD > Now bar is enabled.\n\n"
                            + "If “Live notifications for all apps” is missing, select Samsung "
                            + "compatibility above. Samsung changes third-party access by model, "
                            + "region, and firmware build even when Android and One UI versions "
                            + "match.\n\n"
                            + "If both modes remain ordinary notifications, that firmware or "
                            + "device does not expose a third-party Now Bar surface. Codex Meter "
                            + "cannot override Samsung’s system allowlist.")
                    .setNeutralButton("Developer options", (dialog, which) -> {
                        try {
                            startActivity(new Intent(
                                    Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
                        } catch (RuntimeException exception) {
                            Toast.makeText(requireContext(),
                                    "Developer options are not available on this firmware.",
                                    Toast.LENGTH_LONG).show();
                        }
                    })
                    .setPositiveButton("Done", null)
                    .show();
        }

        private void saveNowBarAutoStart(boolean enabled, String metric, int threshold) {
            NowBarPreferences.save(requireContext(), enabled, metric, threshold);
            updateNowBarAutoStartEnabledState();
            if (enabled) {
                NowBarPreferences.clearSuppression(requireContext());
                boolean started = NowBarManager.maybeAutoStart(requireContext(),
                        AppPreferences.loadSnapshot(requireContext()));
                if (started) {
                    Toast.makeText(requireContext(),
                            "Live monitor started from the current usage threshold.",
                            Toast.LENGTH_SHORT).show();
                }
            }
            updateNowBarSummary();
            PhoneWearSync.pushSettings(requireContext());
        }

        private void updateNowBarAutoStartEnabledState() {
            boolean enabled = NowBarPreferences.isAutoStartEnabled(requireContext());
            if (nowBarMetricPreference != null) nowBarMetricPreference.setEnabled(enabled);
            if (nowBarThresholdPreference != null) nowBarThresholdPreference.setEnabled(enabled);
        }

        private boolean ensureNotificationPermission() {
            if (Build.VERSION.SDK_INT >= 33
                    && requireContext().checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 8602);
                Toast.makeText(requireContext(),
                        "Allow notifications, then start the monitor again.", Toast.LENGTH_LONG).show();
                return false;
            }
            if (NowBarManager.canPostNotifications(requireContext())) return true;
            Toast.makeText(requireContext(),
                    "Enable app notifications, then start the monitor again.",
                    Toast.LENGTH_LONG).show();
            return false;
        }

        private void updateNowBarSummary() {
            if (nowBarMonitorPreference == null || getContext() == null) return;
            boolean active = NowBarManager.isActive(requireContext());
            nowBarMonitorPreference.setChecked(active);
            if (active) {
                String kind = NowBarManager.isPreview(requireContext()) ? "Sample preview" : "Live monitor";
                boolean samsungCompatibility = NowBarDisplayMode.SAMSUNG_COMPATIBILITY.equals(
                        NowBarManager.postedDisplayMode(requireContext()));
                String state = samsungCompatibility
                        ? "using Samsung compatibility"
                        : Build.VERSION.SDK_INT >= 36
                        ? (NowBarManager.isPromoted(requireContext())
                        ? "promoted as a Live Update"
                        : "active, but not promoted by the system")
                        : "active";
                nowBarMonitorPreference.setSummary(kind + " " + state + " · ends "
                        + UsageFormat.absolute(requireContext(), NowBarManager.activeUntil(requireContext()),
                        System.currentTimeMillis()));
            } else if (NowBarPreferences.isAutoStartEnabled(requireContext())) {
                nowBarMonitorPreference.setSummary(
                        "Waiting to auto-start when remaining allowance hits the threshold");
            } else {
                nowBarMonitorPreference.setSummary(
                        "Show remaining Codex allowance until the next available usage reset");
            }
            if (nowBarAutoStartPreference != null) {
                nowBarAutoStartPreference.setChecked(
                        NowBarPreferences.isAutoStartEnabled(requireContext()));
            }
            if (nowBarDisplayModePreference != null) {
                nowBarDisplayModePreference.setValue(
                        NowBarPreferences.getDisplayMode(requireContext()));
            }
            if (nowBarPercentModePreference != null) {
                nowBarPercentModePreference.setValue(
                        NowBarPreferences.getPercentMode(requireContext()));
            }
            if (nowBarPermissionPreference != null) {
                String summary;
                if (!NowBarManager.canPostNotifications(requireContext())) {
                    summary = "App or live-monitor notifications disabled · tap to enable";
                } else if (NowBarDisplayMode.SAMSUNG_COMPATIBILITY.equals(
                        NowBarManager.postedDisplayMode(requireContext()))) {
                    summary = NowBarDisplayMode.AUTO.equals(
                            NowBarPreferences.getDisplayMode(requireContext()))
                            ? "Automatic · using Samsung fallback until Android access is allowed"
                            : "Samsung compatibility selected · firmware support required";
                } else if (Build.VERSION.SDK_INT < 36) {
                    summary = "Notifications allowed · Live display depends on your device";
                } else if (!NowBarManager.canPostPromotedNotifications(requireContext())) {
                    summary = "Live notifications not allowed · tap to enable";
                } else if (active && NowBarManager.isPromoted(requireContext())) {
                    summary = "Live notification promoted by Android";
                } else if (active) {
                    summary = "Access allowed · active notification was not promoted";
                } else {
                    summary = "Live notifications allowed";
                }
                nowBarPermissionPreference.setSummary(summary);
            }
        }

        private void setNotificationsEnabled(boolean enabled) {
            saveAlert(enabled ? ResetAlertPreferences.STYLE_NOTIFICATION : ResetAlertPreferences.STYLE_OFF,
                    ResetAlertPreferences.getMetric(requireContext()),
                    ResetAlertPreferences.getThreshold(requireContext()));
            if (enabled && Build.VERSION.SDK_INT >= 33
                    && requireContext().checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 8601);
            }
            if (enabled) {
                ResetNotificationManager.ensureChannel(requireContext());
            } else {
                ResetNotificationManager.clearNotificationHistory(requireContext());
            }
        }

        private void saveAlert(String style, String metric, int threshold) {
            ResetAlertPreferences.save(requireContext(), style, metric, threshold);
            if (!ResetAlertPreferences.STYLE_OFF.equals(style)) {
                ResetNotificationManager.ensureChannel(requireContext());
                ResetNotificationManager.onUsageUpdated(requireContext(), AppPreferences.loadSnapshot(requireContext()));
                ResetNotificationManager.onResetCreditsUpdated(requireContext(), AppPreferences.loadResetCredits(requireContext()));
            }
            ResetAlertScheduler.scheduleFromSnapshot(requireContext(), AppPreferences.loadSnapshot(requireContext()));
            scheduleResetCreditExpiryReminders();
        }

        private void updatePermissionSummary() {
            if (permissionPreference == null || getContext() == null) return;
            NotificationManager manager = (NotificationManager) requireContext().getSystemService(NOTIFICATION_SERVICE);
            boolean allowed = manager != null && manager.areNotificationsEnabled()
                    && (Build.VERSION.SDK_INT < 33
                    || requireContext().checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    == PackageManager.PERMISSION_GRANTED);
            permissionPreference.setSummary(allowed ? "Allowed" : "Not allowed");
            if (testNotificationPreference != null) {
                testNotificationPreference.setEnabled(allowed && ResetAlertPreferences.enabled(requireContext()));
            }
        }
    }
}
