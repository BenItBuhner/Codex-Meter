package dev.bennett.codexmeter;

import android.app.NotificationManager;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreferenceCompat;
import dev.oneuiproject.oneui.layout.ToolbarLayout;
import dev.oneuiproject.oneui.preference.HorizontalRadioPreference;
import dev.oneuiproject.oneui.preference.LayoutPreference;
import dev.oneuiproject.oneui.widget.RoundedLinearLayout;
import dev.oneuiproject.oneui.widget.CardItemView;

/** Settings built from the One UI Design Library preference components used by its sample app. */
public final class SettingsActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle bundle) {
        Ui.applySelectedTheme(this);
        super.onCreate(bundle);
        AppPreferences.setAppStyle(this, WidgetOptions.SURFACE_ONE_UI);
        setContentView(R.layout.activity_settings);
        ToolbarLayout toolbar = findViewById(R.id.settings_toolbar_layout);
        toolbar.setTitle("Settings");
        toolbar.setExpandable(false);
        toolbar.setExpanded(false, false);
        toolbar.setShowNavigationButtonAsBack(true);
        Ui.configureSystemBars(this, toolbar, Ui.isDark(this));
        if (bundle == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.settings_fragment, new SettingsFragment())
                    .commit();
        }
    }

    public static final class SettingsFragment extends PreferenceFragmentCompat {
        private Preference permissionPreference;

        @Override
        public void onCreatePreferences(Bundle bundle, String rootKey) {
            getPreferenceManager().setSharedPreferencesName("codex_meter_settings_v1");
            addPreferencesFromResource(R.xml.preferences_settings);
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

            permissionPreference = findPreference("notification_permission");
            permissionPreference.setOnPreferenceClickListener(preference -> {
                startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().getPackageName()));
                return true;
            });
            updatePermissionSummary();
        }

        private void setNotificationsEnabled(boolean enabled) {
            saveAlert(enabled ? ResetAlertPreferences.STYLE_NOTIFICATION : ResetAlertPreferences.STYLE_OFF,
                    ResetAlertPreferences.getMetric(requireContext()),
                    ResetAlertPreferences.getThreshold(requireContext()));
            if (enabled && Build.VERSION.SDK_INT >= 33
                    && requireContext().checkSelfPermission("android.permission.POST_NOTIFICATIONS") != 0) {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 8601);
            }
        }

        private void saveAlert(String style, String metric, int threshold) {
            ResetAlertPreferences.save(requireContext(), style, metric, threshold);
            ResetAlertScheduler.scheduleFromSnapshot(requireContext(), AppPreferences.loadSnapshot(requireContext()));
        }

        private void updatePermissionSummary() {
            if (permissionPreference == null || getContext() == null) return;
            NotificationManager manager = (NotificationManager) requireContext().getSystemService(NOTIFICATION_SERVICE);
            permissionPreference.setSummary(manager.areNotificationsEnabled() ? "Allowed" : "Not allowed");
        }
    }
}
