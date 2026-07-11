package dev.bennett.codexmeter;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import dev.bennett.codexmeter.SamsungLockWidgetSupport;
import java.util.List;

/* JADX INFO: loaded from: classes.dex */
public final class SettingsActivity extends Activity {
    private Spinner accentSpinner;
    private Spinner alertMetricSpinner;
    private Spinner alertStyleSpinner;
    private Spinner alertThresholdSpinner;
    private Spinner appStyleSpinner;
    private Spinner appThemeSpinner;
    private LinearLayout content;
    private boolean dark;
    private Spinner densitySpinner;
    private Spinner displaySpinner;
    private Spinner graphicSpinner;
    private Spinner metricSpinner;
    private Spinner opacitySpinner;
    private Spinner refreshSpinner;
    private Spinner resetSpinner;
    private CheckBox showPlan;
    private CheckBox showRefresh;
    private CheckBox showResetAction;
    private CheckBox showResetCredits;
    private CheckBox showTitle;
    private CheckBox showUpdated;
    private Spinner styleSpinner;
    private Spinner surfaceSpinner;
    private Spinner widgetThemeSpinner;
    private static final String[] REFRESH_LABELS = {"5 minutes", "10 minutes", "15 minutes", "30 minutes", "60 minutes", "2 hours"};
    private static final int[] REFRESH_VALUES = {5, 10, 15, 30, 60, 120};
    private static final String[] ALERT_STYLE_LABELS = {"Off", "Silent reminder", "Notification sound", "Alarm sound"};
    private static final String[] ALERT_STYLE_VALUES = {ResetAlertPreferences.STYLE_OFF, ResetAlertPreferences.STYLE_SILENT, ResetAlertPreferences.STYLE_NOTIFICATION, ResetAlertPreferences.STYLE_ALARM};
    private static final String[] ALERT_METRIC_LABELS = {"Both limits", "5-hour only", "Weekly only"};
    private static final String[] ALERT_METRIC_VALUES = {"both", "five_hour", "weekly"};
    private static final String[] ALERT_THRESHOLD_LABELS = {"Always", "10% or less", "25% or less", "50% or less", "75% or less"};
    private static final int[] ALERT_THRESHOLD_VALUES = {100, 10, 25, 50, 75};

    @Override // android.app.Activity
    protected void onCreate(Bundle bundle) {
        Ui.applySelectedTheme(this);
        super.onCreate(bundle);
        this.dark = Ui.isDark(this);
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setClipToPadding(false);
        scrollView.setBackgroundColor(Ui.background(this, this.dark));
        this.content = new LinearLayout(this);
        this.content.setOrientation(1);
        int iPageHorizontalPadding = Ui.pageHorizontalPadding(this);
        this.content.setPadding(iPageHorizontalPadding, Ui.pageTopPadding(this), iPageHorizontalPadding, Ui.dp(this, 42.0f));
        scrollView.addView(this.content, new FrameLayout.LayoutParams(-1, -2));
        setContentView(scrollView);
        Ui.configureSystemBars(this, scrollView, this.dark);
        build();
    }

    @Override // android.app.Activity
    protected void onResume() {
        super.onResume();
        if (this.content != null) {
            buildWidgetList();
        }
    }

    private void build() {
        String str;
        this.content.removeAllViews();
        LinearLayout linearLayoutHorizontal = Ui.horizontal(this, 16);
        Button buttonBackAction = Ui.backAction(this, this.dark);
        buttonBackAction.setOnClickListener(new View.OnClickListener() { // from class: dev.bennett.codexmeter.SettingsActivity.1
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                SettingsActivity.this.finish();
            }
        });
        linearLayoutHorizontal.addView(buttonBackAction, new LinearLayout.LayoutParams(Ui.dp(this, 48.0f), Ui.dp(this, 48.0f)));
        TextView textViewTitle = Ui.title(this, "Settings", this.dark);
        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(0, -2, 1.0f);
        layoutParams.setMargins(Ui.dp(this, 12.0f), 0, 0, 0);
        linearLayoutHorizontal.addView(textViewTitle, layoutParams);
        this.content.addView(linearLayoutHorizontal);
        this.content.addView(Ui.sectionTitle(this, "Refresh", this.dark));
        LinearLayout linearLayoutCard = Ui.card(this, this.dark);
        this.refreshSpinner = Ui.spinner(this, REFRESH_LABELS, this.dark);
        selectInt(this.refreshSpinner, REFRESH_VALUES, AppPreferences.getRefreshMinutes(this));
        Ui.addLabeledSpinner(linearLayoutCard, "Automatic interval", this.refreshSpinner, this.dark);
        TextView textViewText = Ui.text(this, "Five- and ten-minute intervals use best-effort one-shot jobs. Android may batch or delay them under Doze, battery saving, or background restrictions. Tapping refresh still requests an immediate update.", 12.0f, Ui.secondaryText(this.dark));
        LinearLayout.LayoutParams layoutParams2 = new LinearLayout.LayoutParams(-1, -2);
        layoutParams2.setMargins(0, Ui.dp(this, 14.0f), 0, 0);
        linearLayoutCard.addView(textViewText, layoutParams2);
        this.content.addView(linearLayoutCard);
        this.content.addView(Ui.sectionTitle(this, "Reset notifications", this.dark));
        LinearLayout linearLayoutCard2 = Ui.card(this, this.dark);
        this.alertStyleSpinner = Ui.spinner(this, ALERT_STYLE_LABELS, this.dark);
        this.alertMetricSpinner = Ui.spinner(this, ALERT_METRIC_LABELS, this.dark);
        this.alertThresholdSpinner = Ui.spinner(this, ALERT_THRESHOLD_LABELS, this.dark);
        selectString(this.alertStyleSpinner, ALERT_STYLE_VALUES, ResetAlertPreferences.getStyle(this));
        selectString(this.alertMetricSpinner, ALERT_METRIC_VALUES, ResetAlertPreferences.getMetric(this));
        selectInt(this.alertThresholdSpinner, ALERT_THRESHOLD_VALUES, ResetAlertPreferences.getThreshold(this));
        Ui.addLabeledSpinner(linearLayoutCard2, "Alert style", this.alertStyleSpinner, this.dark);
        Ui.addLabeledSpinner(linearLayoutCard2, "Monitor", this.alertMetricSpinner, this.dark);
        Ui.addLabeledSpinner(linearLayoutCard2, "Schedule after remaining usage reaches", this.alertThresholdSpinner, this.dark);
        TextView textViewText2 = Ui.text(this, "Codex Meter schedules a local alarm from OpenAI's existing reset timestamp. It does not poll the server to keep the countdown or reminder current.", 12.0f, Ui.secondaryText(this.dark));
        LinearLayout.LayoutParams layoutParams3 = new LinearLayout.LayoutParams(-1, -2);
        layoutParams3.setMargins(0, Ui.dp(this, 14.0f), 0, 0);
        linearLayoutCard2.addView(textViewText2, layoutParams3);
        if (Build.VERSION.SDK_INT >= 31) {
            Button button = Ui.button(this, ResetAlertScheduler.canScheduleExact(this) ? "Precise alarm access enabled" : "Allow precise alarm timing", false, this.dark);
            LinearLayout.LayoutParams layoutParams4 = new LinearLayout.LayoutParams(-1, Ui.dp(this, 48.0f));
            layoutParams4.setMargins(0, Ui.dp(this, 12.0f), 0, 0);
            linearLayoutCard2.addView(button, layoutParams4);
            button.setOnClickListener(new View.OnClickListener() { // from class: dev.bennett.codexmeter.SettingsActivity.2
                @Override // android.view.View.OnClickListener
                public void onClick(View view) {
                    try {
                        SettingsActivity.this.startActivity(new Intent("android.settings.REQUEST_SCHEDULE_EXACT_ALARM", Uri.parse("package:" + SettingsActivity.this.getPackageName())));
                    } catch (RuntimeException e) {
                        Toast.makeText(SettingsActivity.this, "Precise alarm settings are unavailable on this device.", 1).show();
                    }
                }
            });
        }
        this.content.addView(linearLayoutCard2);
        this.content.addView(Ui.sectionTitle(this, "App appearance", this.dark));
        LinearLayout linearLayoutCard3 = Ui.card(this, this.dark);
        this.appThemeSpinner = Ui.spinner(this, WidgetOptionCatalog.THEME_LABELS, this.dark);
        this.appStyleSpinner = Ui.spinner(this, WidgetOptionCatalog.SURFACE_LABELS, this.dark);
        selectString(this.appThemeSpinner, WidgetOptionCatalog.THEME_VALUES, AppPreferences.getAppTheme(this));
        selectString(this.appStyleSpinner, WidgetOptionCatalog.SURFACE_VALUES, AppPreferences.getAppStyle(this));
        Ui.addLabeledSpinner(linearLayoutCard3, "Theme", this.appThemeSpinner, this.dark);
        Ui.addLabeledSpinner(linearLayoutCard3, "Interface style", this.appStyleSpinner, this.dark);
        TextView textViewText3 = Ui.text(this, "Material 3 expressive follows Android dynamic color and pill-shaped controls. One UI 8+ uses Samsung blue, larger viewing-area headers, flat grouped cards, compact action pills, and Galaxy-style spacing.", 12.0f, Ui.secondaryText(this.dark));
        LinearLayout.LayoutParams layoutParams5 = new LinearLayout.LayoutParams(-1, -2);
        layoutParams5.setMargins(0, Ui.dp(this, 14.0f), 0, 0);
        linearLayoutCard3.addView(textViewText3, layoutParams5);
        this.content.addView(linearLayoutCard3);
        this.content.addView(Ui.sectionTitle(this, "Samsung system surfaces", this.dark));
        LinearLayout linearLayoutCard4 = Ui.card(this, this.dark);
        TextView textViewText4 = Ui.text(this, "One UI Home and lock screen", 16.0f, Ui.mainText(this.dark));
        textViewText4.setTypeface(Ui.mediumTypeface(this));
        linearLayoutCard4.addView(textViewText4);
        boolean z = Build.MANUFACTURER != null && "samsung".equalsIgnoreCase(Build.MANUFACTURER);
        AppWidgetManager.getInstance(this);
        int iCountAll = SamsungLockWidgetSupport.countAll(this);
        if (z) {
            str = "Galaxy integration is enabled. One UI widgets can use the launcher-managed frame and blur. The lock-screen picker receives numbers, rings, gauges, and bars in both square and wide sizes.";
        } else {
            str = "The Samsung metadata is packaged harmlessly and is ignored by other launchers. Standard Android widgets remain available everywhere.";
        }
        TextView textViewText5 = Ui.text(this, str, 12.0f, Ui.secondaryText(this.dark));
        LinearLayout.LayoutParams layoutParams6 = new LinearLayout.LayoutParams(-1, -2);
        layoutParams6.setMargins(0, Ui.dp(this, 8.0f), 0, 0);
        linearLayoutCard4.addView(textViewText5, layoutParams6);
        TextView textViewText6 = Ui.text(this, iCountAll > 0 ? "Lock-screen tile active" : "To add a tile on a compatible Galaxy: edit the lock screen, open Widgets, then choose a Codex Meter style and its square or wide size.", 12.0f, iCountAll > 0 ? Ui.accent(this, this.dark) : Ui.secondaryText(this.dark));
        textViewText6.setTypeface(iCountAll > 0 ? Ui.mediumTypeface(this) : Ui.regularTypeface(this));
        LinearLayout.LayoutParams layoutParams7 = new LinearLayout.LayoutParams(-1, -2);
        layoutParams7.setMargins(0, Ui.dp(this, 12.0f), 0, 0);
        linearLayoutCard4.addView(textViewText6, layoutParams7);
        this.content.addView(linearLayoutCard4);
        this.content.addView(Ui.sectionTitle(this, "New widget defaults", this.dark));
        LinearLayout linearLayoutCard5 = Ui.card(this, this.dark);
        WidgetOptions widgetOptionsLoadDefaultWidgetOptions = AppPreferences.loadDefaultWidgetOptions(this);
        this.styleSpinner = Ui.spinner(this, WidgetOptionCatalog.STYLE_LABELS, this.dark);
        this.densitySpinner = Ui.spinner(this, WidgetOptionCatalog.DENSITY_LABELS, this.dark);
        this.surfaceSpinner = Ui.spinner(this, WidgetOptionCatalog.SURFACE_LABELS, this.dark);
        this.graphicSpinner = Ui.spinner(this, WidgetOptionCatalog.GRAPHIC_LABELS, this.dark);
        this.widgetThemeSpinner = Ui.spinner(this, WidgetOptionCatalog.THEME_LABELS, this.dark);
        this.accentSpinner = Ui.spinner(this, WidgetOptionCatalog.ACCENT_LABELS, this.dark);
        this.opacitySpinner = Ui.spinner(this, WidgetOptionCatalog.OPACITY_LABELS, this.dark);
        this.resetSpinner = Ui.spinner(this, WidgetOptionCatalog.RESET_LABELS, this.dark);
        this.displaySpinner = Ui.spinner(this, WidgetOptionCatalog.DISPLAY_LABELS, this.dark);
        this.metricSpinner = Ui.spinner(this, WidgetOptionCatalog.METRIC_LABELS, this.dark);
        selectString(this.styleSpinner, WidgetOptionCatalog.STYLE_VALUES, widgetOptionsLoadDefaultWidgetOptions.layout);
        selectString(this.densitySpinner, WidgetOptionCatalog.DENSITY_VALUES, widgetOptionsLoadDefaultWidgetOptions.density);
        selectString(this.surfaceSpinner, WidgetOptionCatalog.SURFACE_VALUES, widgetOptionsLoadDefaultWidgetOptions.surfaceStyle);
        selectString(this.graphicSpinner, WidgetOptionCatalog.GRAPHIC_VALUES, widgetOptionsLoadDefaultWidgetOptions.graphicScale);
        selectString(this.widgetThemeSpinner, WidgetOptionCatalog.THEME_VALUES, widgetOptionsLoadDefaultWidgetOptions.theme);
        selectString(this.accentSpinner, WidgetOptionCatalog.ACCENT_VALUES, widgetOptionsLoadDefaultWidgetOptions.accent);
        selectInt(this.opacitySpinner, WidgetOptionCatalog.OPACITY_VALUES, widgetOptionsLoadDefaultWidgetOptions.opacity);
        selectString(this.resetSpinner, WidgetOptionCatalog.RESET_VALUES, widgetOptionsLoadDefaultWidgetOptions.resetMode);
        selectString(this.displaySpinner, WidgetOptionCatalog.DISPLAY_VALUES, widgetOptionsLoadDefaultWidgetOptions.displayMode);
        selectString(this.metricSpinner, WidgetOptionCatalog.METRIC_VALUES, widgetOptionsLoadDefaultWidgetOptions.metricMode);
        Ui.addLabeledSpinner(linearLayoutCard5, "Layout", this.styleSpinner, this.dark);
        Ui.addLabeledSpinner(linearLayoutCard5, "Visual language", this.surfaceSpinner, this.dark);
        Ui.addLabeledSpinner(linearLayoutCard5, "Ring and dial size", this.graphicSpinner, this.dark);
        Ui.addLabeledSpinner(linearLayoutCard5, "Density", this.densitySpinner, this.dark);
        Ui.addLabeledSpinner(linearLayoutCard5, "Widget theme", this.widgetThemeSpinner, this.dark);
        Ui.addLabeledSpinner(linearLayoutCard5, "Accent", this.accentSpinner, this.dark);
        Ui.addLabeledSpinner(linearLayoutCard5, "Background", this.opacitySpinner, this.dark);
        Ui.addLabeledSpinner(linearLayoutCard5, "Reset time", this.resetSpinner, this.dark);
        Ui.addLabeledSpinner(linearLayoutCard5, "Percentage", this.displaySpinner, this.dark);
        Ui.addLabeledSpinner(linearLayoutCard5, "Usage windows", this.metricSpinner, this.dark);
        this.showTitle = Ui.checkbox(this, "Show Codex title", widgetOptionsLoadDefaultWidgetOptions.showTitle, this.dark);
        this.showPlan = Ui.checkbox(this, "Show plan label", widgetOptionsLoadDefaultWidgetOptions.showPlan, this.dark);
        this.showUpdated = Ui.checkbox(this, "Show updated time", widgetOptionsLoadDefaultWidgetOptions.showUpdated, this.dark);
        this.showRefresh = Ui.checkbox(this, "Show refresh control", widgetOptionsLoadDefaultWidgetOptions.showRefresh, this.dark);
        this.showResetCredits = Ui.checkbox(this, "Show reset-credit status", widgetOptionsLoadDefaultWidgetOptions.showResetCredits, this.dark);
        this.showResetAction = Ui.checkbox(this, "Show Use reset button", widgetOptionsLoadDefaultWidgetOptions.showResetAction, this.dark);
        linearLayoutCard5.addView(this.showTitle);
        linearLayoutCard5.addView(this.showPlan);
        linearLayoutCard5.addView(this.showUpdated);
        linearLayoutCard5.addView(this.showRefresh);
        linearLayoutCard5.addView(this.showResetCredits);
        linearLayoutCard5.addView(this.showResetAction);
        this.content.addView(linearLayoutCard5);
        this.content.addView(Ui.sectionTitle(this, "Placed widgets", this.dark));
        LinearLayout linearLayout = new LinearLayout(this);
        linearLayout.setId(android.R.id.list);
        linearLayout.setOrientation(1);
        this.content.addView(linearLayout);
        buildWidgetList();
        Button button2 = Ui.button(this, "Save settings", true, this.dark);
        LinearLayout.LayoutParams layoutParams8 = new LinearLayout.LayoutParams(-1, Ui.dp(this, 54.0f));
        layoutParams8.setMargins(0, Ui.dp(this, 26.0f), 0, 0);
        this.content.addView(button2, layoutParams8);
        button2.setOnClickListener(new View.OnClickListener() { // from class: dev.bennett.codexmeter.SettingsActivity.3
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                SettingsActivity.this.save();
            }
        });
        LinearLayout linearLayoutCard6 = Ui.card(this, this.dark);
        TextView textViewText7 = Ui.text(this, "Local data and privacy", 16.0f, Ui.mainText(this.dark));
        textViewText7.setTypeface(Ui.mediumTypeface(this));
        linearLayoutCard6.addView(textViewText7);
        TextView textViewText8 = Ui.text(this, "OAuth tokens are encrypted with Android Keystore. Backups are disabled. The app sends requests only to OpenAI authentication and ChatGPT Codex endpoints and contains no analytics SDK.", 12.0f, Ui.secondaryText(this.dark));
        LinearLayout.LayoutParams layoutParams9 = new LinearLayout.LayoutParams(-1, -2);
        layoutParams9.setMargins(0, Ui.dp(this, 9.0f), 0, 0);
        linearLayoutCard6.addView(textViewText8, layoutParams9);
        LinearLayout.LayoutParams layoutParams10 = new LinearLayout.LayoutParams(-1, -2);
        layoutParams10.setMargins(0, Ui.dp(this, 26.0f), 0, 0);
        this.content.addView(linearLayoutCard6, layoutParams10);
    }

    private void buildWidgetList() {
        String str;
        LinearLayout linearLayout = (LinearLayout) this.content.findViewById(android.R.id.list);
        if (linearLayout != null) {
            linearLayout.removeAllViews();
            int[] appWidgetIds = AppWidgetManager.getInstance(this).getAppWidgetIds(new ComponentName(this, (Class<?>) CodexUsageWidget.class));
            List<SamsungLockWidgetSupport.LockInstance> listPlacedWidgets = SamsungLockWidgetSupport.placedWidgets(this);
            if (appWidgetIds.length == 0 && listPlacedWidgets.isEmpty()) {
                LinearLayout linearLayoutCard = Ui.card(this, this.dark);
                linearLayoutCard.addView(Ui.text(this, "No widget has been placed yet. Add one from the main screen or your launcher’s widget picker.", 13.0f, Ui.secondaryText(this.dark)));
                linearLayout.addView(linearLayoutCard);
                return;
            }
            int length = appWidgetIds.length;
            int i = 0;
            int i2 = 0;
            while (i2 < length) {
                final int i3 = appWidgetIds[i2];
                WidgetOptions widgetOptionsLoadWidgetOptions = AppPreferences.loadWidgetOptions(this, i3);
                Button button = Ui.button(this, "Home · " + styleLabel(widgetOptionsLoadWidgetOptions.layout) + " · " + (WidgetOptions.SURFACE_ONE_UI.equals(widgetOptionsLoadWidgetOptions.surfaceStyle) ? "One UI" : "Material"), false, this.dark);
                LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(-1, Ui.dp(this, 54.0f));
                int i4 = i + 1;
                if (i > 0) {
                    layoutParams.setMargins(0, Ui.dp(this, 10.0f), 0, 0);
                }
                linearLayout.addView(button, layoutParams);
                button.setOnClickListener(new View.OnClickListener() { // from class: dev.bennett.codexmeter.SettingsActivity.4
                    @Override // android.view.View.OnClickListener
                    public void onClick(View view) {
                        SettingsActivity.this.startActivity(new Intent(SettingsActivity.this, (Class<?>) WidgetConfigActivity.class).putExtra("appWidgetId", i3));
                    }
                });
                i2++;
                i = i4;
            }
            for (SamsungLockWidgetSupport.LockInstance lockInstance : listPlacedWidgets) {
                final int i5 = lockInstance.appWidgetId;
                LockWidgetOptions lockWidgetOptionsLoadLockWidgetOptions = AppPreferences.loadLockWidgetOptions(this, i5);
                if ("five_hour".equals(lockWidgetOptionsLoadLockWidgetOptions.metricMode)) {
                    str = "5-hour";
                } else {
                    str = "weekly".equals(lockWidgetOptionsLoadLockWidgetOptions.metricMode) ? "Weekly" : "Both";
                }
                Button button2 = Ui.button(this, "Lock screen · " + lockInstance.label() + " · " + str, false, this.dark);
                LinearLayout.LayoutParams layoutParams2 = new LinearLayout.LayoutParams(-1, Ui.dp(this, 54.0f));
                int i6 = i + 1;
                if (i > 0) {
                    layoutParams2.setMargins(0, Ui.dp(this, 10.0f), 0, 0);
                }
                linearLayout.addView(button2, layoutParams2);
                button2.setOnClickListener(new View.OnClickListener() { // from class: dev.bennett.codexmeter.SettingsActivity.5
                    @Override // android.view.View.OnClickListener
                    public void onClick(View view) {
                        SettingsActivity.this.startActivity(new Intent(SettingsActivity.this, (Class<?>) LockWidgetConfigActivity.class).putExtra("appWidgetId", i5));
                    }
                });
                i = i6;
            }
        }
    }

    public void save() {
        String appTheme = AppPreferences.getAppTheme(this);
        String appStyle = AppPreferences.getAppStyle(this);
        AppPreferences.setRefreshMinutes(this, REFRESH_VALUES[this.refreshSpinner.getSelectedItemPosition()]);
        String str = WidgetOptionCatalog.THEME_VALUES[this.appThemeSpinner.getSelectedItemPosition()];
        String str2 = WidgetOptionCatalog.SURFACE_VALUES[this.appStyleSpinner.getSelectedItemPosition()];
        AppPreferences.setAppTheme(this, str);
        AppPreferences.setAppStyle(this, str2);
        AppPreferences.saveDefaultWidgetOptions(this, new WidgetOptions(WidgetOptionCatalog.STYLE_VALUES[this.styleSpinner.getSelectedItemPosition()], WidgetOptionCatalog.DENSITY_VALUES[this.densitySpinner.getSelectedItemPosition()], WidgetOptionCatalog.SURFACE_VALUES[this.surfaceSpinner.getSelectedItemPosition()], WidgetOptionCatalog.GRAPHIC_VALUES[this.graphicSpinner.getSelectedItemPosition()], WidgetOptionCatalog.THEME_VALUES[this.widgetThemeSpinner.getSelectedItemPosition()], WidgetOptionCatalog.ACCENT_VALUES[this.accentSpinner.getSelectedItemPosition()], WidgetOptionCatalog.OPACITY_VALUES[this.opacitySpinner.getSelectedItemPosition()], WidgetOptionCatalog.RESET_VALUES[this.resetSpinner.getSelectedItemPosition()], WidgetOptionCatalog.DISPLAY_VALUES[this.displaySpinner.getSelectedItemPosition()], WidgetOptionCatalog.METRIC_VALUES[this.metricSpinner.getSelectedItemPosition()], this.showTitle.isChecked(), this.showPlan.isChecked(), this.showUpdated.isChecked(), this.showRefresh.isChecked(), this.showResetCredits.isChecked(), this.showResetAction.isChecked()));
        String str3 = ALERT_STYLE_VALUES[this.alertStyleSpinner.getSelectedItemPosition()];
        ResetAlertPreferences.save(this, str3, ALERT_METRIC_VALUES[this.alertMetricSpinner.getSelectedItemPosition()], ALERT_THRESHOLD_VALUES[this.alertThresholdSpinner.getSelectedItemPosition()]);
        if (!ResetAlertPreferences.STYLE_OFF.equals(str3) && Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != 0) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 8601);
        }
        ResetAlertScheduler.scheduleFromSnapshot(this, AppPreferences.loadSnapshot(this));
        RefreshScheduler.schedulePeriodic(this);
        WidgetRenderer.updateAll(this);
        Toast.makeText(this, "Settings saved.", 0).show();
        if (!appTheme.equals(str) || !appStyle.equals(str2)) {
            recreate();
        }
    }

    private static String styleLabel(String str) {
        for (int i = 0; i < WidgetOptionCatalog.STYLE_VALUES.length; i++) {
            if (WidgetOptionCatalog.STYLE_VALUES[i].equals(str)) {
                return WidgetOptionCatalog.STYLE_LABELS[i];
            }
        }
        return "Adaptive";
    }

    static void selectString(Spinner spinner, String[] strArr, String str) {
        for (int i = 0; i < strArr.length; i++) {
            if (strArr[i].equals(str)) {
                spinner.setSelection(i);
                return;
            }
        }
    }

    static void selectInt(Spinner spinner, int[] iArr, int i) {
        for (int i2 = 0; i2 < iArr.length; i2++) {
            if (iArr[i2] == i) {
                spinner.setSelection(i2);
                return;
            }
        }
    }
}
