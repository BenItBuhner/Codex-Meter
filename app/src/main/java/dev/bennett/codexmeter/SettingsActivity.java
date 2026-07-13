package dev.bennett.codexmeter;

import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreferenceCompat;
import dev.oneuiproject.oneui.layout.ToolbarLayout;
import dev.oneuiproject.oneui.preference.HorizontalRadioPreference;
import dev.oneuiproject.oneui.preference.LayoutPreference;
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
        if (Ui.isOneUi(this)) {
            setContentView(R.layout.activity_settings);
            ToolbarLayout toolbar = findViewById(R.id.settings_toolbar_layout);
            Ui.configureReachToolbar(toolbar, "Settings", true);
        } else {
            setContentView(R.layout.activity_material_settings);
            Toolbar toolbar = findViewById(R.id.settings_toolbar_layout);
            setSupportActionBar(toolbar);
            if (getSupportActionBar() != null) {
                getSupportActionBar().setTitle("Settings");
                getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            }
        }
        if (bundle == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.settings_fragment, new SettingsFragment())
                    .commit();
        }
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            getOnBackPressedDispatcher().onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    public static final class SettingsFragment extends PreferenceFragmentCompat {
        private Preference expiryTimesPreference;
        private Preference permissionPreference;
        private Preference testNotificationPreference;

        @Override
        public void onCreatePreferences(Bundle bundle, String rootKey) {
            getPreferenceManager().setSharedPreferencesName("codex_meter_settings_v1");
            addPreferencesFromResource(Ui.isOneUi(requireContext())
                    ? R.xml.preferences_settings
                    : R.xml.preferences_settings_material);
            bindAccount();
            bindAppearance();
            bindRefresh();
            bindNotifications();
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
        }

        private void bindAccount() {
            boolean dark = Ui.isDark(requireContext());
            LayoutPreference preference = findPreference("account_card");
            if (preference == null) {
                return;
            }
            View card = preference.findViewById(R.id.settings_account_card);
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
            View actionView = preference.findViewById(R.id.settings_account_action);
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
            View.OnClickListener actionListener = view -> {
                if (SecureTokenStore.isSignedIn(requireContext())) {
                    confirmSignOut();
                } else {
                    startActivity(new Intent(requireContext(), MainActivity.class)
                            .putExtra("start_sign_in", true));
                    requireActivity().finish();
                }
            };
            int actionColor = tokens == null
                    ? Ui.accent(requireContext(), dark)
                    : (dark ? 0xFFFF6B6B : 0xFFFF3B30);
            String actionText = tokens == null ? "Sign in with ChatGPT" : "Sign out";
            if (actionView instanceof CardItemView) {
                CardItemView action = (CardItemView) actionView;
                action.getTitleView().setText(actionText);
                action.getTitleView().setTextColor(actionColor);
                action.setOnClickListener(actionListener);
            } else if (actionView instanceof TextView) {
                TextView action = (TextView) actionView;
                action.setText(actionText);
                action.setTextColor(actionColor);
                action.setOnClickListener(actionListener);
            }
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
            ListPreference themeList = findPreference("app_theme_list");
            SwitchPreferenceCompat system = findPreference("theme_system_ui");
            system.setEnabled(true);
            SwitchPreferenceCompat material = findPreference("material_u_mode");
            if (material != null) {
                material.setPersistent(false);
                material.setChecked(WidgetOptions.SURFACE_MATERIAL.equals(
                        AppPreferences.getAppStyle(requireContext())));
                material.setOnPreferenceChangeListener((preference, value) -> {
                    boolean enabled = (Boolean) value;
                    AppPreferences.setAppStyle(requireContext(), enabled
                            ? WidgetOptions.SURFACE_MATERIAL
                            : WidgetOptions.SURFACE_ONE_UI);
                    WidgetRenderer.updateAll(requireContext());
                    requireActivity().recreate();
                    return true;
                });
            }
            if (theme != null) {
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
                theme.setOnPreferenceChangeListener((preference, value) -> {
                    AppPreferences.setAppTheme(requireContext(), String.valueOf(value));
                    requireActivity().recreate();
                    return true;
                });
            }
            if (themeList != null) {
                themeList.setPersistent(false);
                themeList.setValue(useSystem
                        ? (Ui.isDark(requireContext()) ? WidgetOptions.THEME_DARK : WidgetOptions.THEME_LIGHT)
                        : selected);
                themeList.setEnabled(!useSystem);
                themeList.setOnPreferenceChangeListener((preference, value) -> {
                    AppPreferences.setAppTheme(requireContext(), String.valueOf(value));
                    requireActivity().recreate();
                    return true;
                });
            }
            system.setChecked(useSystem);
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
                return true;
            });
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
