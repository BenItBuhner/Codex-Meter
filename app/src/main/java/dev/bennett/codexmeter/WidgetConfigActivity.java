package dev.bennett.codexmeter;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

/* JADX INFO: loaded from: classes.dex */
public final class WidgetConfigActivity extends Activity {
    private Spinner accentSpinner;
    private int appWidgetId = 0;
    private boolean dark;
    private Spinner densitySpinner;
    private Spinner displaySpinner;
    private Spinner graphicSpinner;
    private Spinner metricSpinner;
    private Spinner opacitySpinner;
    private WidgetPreviewView preview;
    private Spinner resetSpinner;
    private CheckBox showPlan;
    private CheckBox showRefresh;
    private CheckBox showResetAction;
    private CheckBox showResetCredits;
    private CheckBox showTitle;
    private CheckBox showUpdated;
    private Spinner styleSpinner;
    private Spinner surfaceSpinner;
    private Spinner themeSpinner;

    @Override // android.app.Activity
    protected void onCreate(Bundle bundle) {
        Ui.applySelectedTheme(this);
        super.onCreate(bundle);
        setResult(0);
        this.appWidgetId = getIntent().getIntExtra("appWidgetId", 0);
        if (this.appWidgetId == 0) {
            Toast.makeText(this, "No widget was selected.", 1).show();
            finish();
        } else {
            this.dark = Ui.isDark(this);
            build();
        }
    }

    private void build() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setClipToPadding(false);
        scrollView.setBackgroundColor(Ui.background(this, this.dark));
        LinearLayout linearLayout = new LinearLayout(this);
        linearLayout.setOrientation(1);
        int iPageHorizontalPadding = Ui.pageHorizontalPadding(this);
        linearLayout.setPadding(iPageHorizontalPadding, Ui.pageTopPadding(this), iPageHorizontalPadding, Ui.dp(this, 42.0f));
        scrollView.addView(linearLayout, new FrameLayout.LayoutParams(-1, -2));
        setContentView(scrollView);
        Ui.configureSystemBars(this, scrollView, this.dark);
        LinearLayout linearLayoutHorizontal = Ui.horizontal(this, 16);
        Button buttonBackAction = Ui.backAction(this, this.dark);
        buttonBackAction.setOnClickListener(new View.OnClickListener() { // from class: dev.bennett.codexmeter.WidgetConfigActivity.1
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                WidgetConfigActivity.this.finish();
            }
        });
        linearLayoutHorizontal.addView(buttonBackAction, new LinearLayout.LayoutParams(Ui.dp(this, 48.0f), Ui.dp(this, 48.0f)));
        TextView textViewTitle = Ui.title(this, "Customize widget", this.dark);
        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(0, -2, 1.0f);
        layoutParams.setMargins(Ui.dp(this, 12.0f), 0, 0, 0);
        linearLayoutHorizontal.addView(textViewTitle, layoutParams);
        linearLayout.addView(linearLayoutHorizontal);
        View viewText = Ui.text(this, "These settings apply only to this widget. Resize it afterward and the graphics will adapt to the available space.", 13.0f, Ui.secondaryText(this.dark));
        LinearLayout.LayoutParams layoutParams2 = new LinearLayout.LayoutParams(-1, -2);
        layoutParams2.setMargins(0, Ui.dp(this, 8.0f), 0, 0);
        linearLayout.addView(viewText, layoutParams2);
        linearLayout.addView(Ui.sectionTitle(this, "Preview", this.dark));
        this.preview = new WidgetPreviewView(this);
        linearLayout.addView(this.preview, new LinearLayout.LayoutParams(-1, Ui.dp(this, 250.0f)));
        linearLayout.addView(Ui.sectionTitle(this, "Design", this.dark));
        LinearLayout linearLayoutCard = Ui.card(this, this.dark);
        WidgetOptions widgetOptionsLoadWidgetOptions = AppPreferences.loadWidgetOptions(this, this.appWidgetId);
        this.styleSpinner = Ui.spinner(this, WidgetOptionCatalog.STYLE_LABELS, this.dark);
        this.densitySpinner = Ui.spinner(this, WidgetOptionCatalog.DENSITY_LABELS, this.dark);
        this.surfaceSpinner = Ui.spinner(this, WidgetOptionCatalog.SURFACE_LABELS, this.dark);
        this.graphicSpinner = Ui.spinner(this, WidgetOptionCatalog.GRAPHIC_LABELS, this.dark);
        this.themeSpinner = Ui.spinner(this, WidgetOptionCatalog.THEME_LABELS, this.dark);
        this.accentSpinner = Ui.spinner(this, WidgetOptionCatalog.ACCENT_LABELS, this.dark);
        this.opacitySpinner = Ui.spinner(this, WidgetOptionCatalog.OPACITY_LABELS, this.dark);
        this.resetSpinner = Ui.spinner(this, WidgetOptionCatalog.RESET_LABELS, this.dark);
        this.displaySpinner = Ui.spinner(this, WidgetOptionCatalog.DISPLAY_LABELS, this.dark);
        this.metricSpinner = Ui.spinner(this, WidgetOptionCatalog.METRIC_LABELS, this.dark);
        SettingsActivity.selectString(this.styleSpinner, WidgetOptionCatalog.STYLE_VALUES, widgetOptionsLoadWidgetOptions.layout);
        SettingsActivity.selectString(this.densitySpinner, WidgetOptionCatalog.DENSITY_VALUES, widgetOptionsLoadWidgetOptions.density);
        SettingsActivity.selectString(this.surfaceSpinner, WidgetOptionCatalog.SURFACE_VALUES, widgetOptionsLoadWidgetOptions.surfaceStyle);
        SettingsActivity.selectString(this.graphicSpinner, WidgetOptionCatalog.GRAPHIC_VALUES, widgetOptionsLoadWidgetOptions.graphicScale);
        SettingsActivity.selectString(this.themeSpinner, WidgetOptionCatalog.THEME_VALUES, widgetOptionsLoadWidgetOptions.theme);
        SettingsActivity.selectString(this.accentSpinner, WidgetOptionCatalog.ACCENT_VALUES, widgetOptionsLoadWidgetOptions.accent);
        SettingsActivity.selectInt(this.opacitySpinner, WidgetOptionCatalog.OPACITY_VALUES, widgetOptionsLoadWidgetOptions.opacity);
        SettingsActivity.selectString(this.resetSpinner, WidgetOptionCatalog.RESET_VALUES, widgetOptionsLoadWidgetOptions.resetMode);
        SettingsActivity.selectString(this.displaySpinner, WidgetOptionCatalog.DISPLAY_VALUES, widgetOptionsLoadWidgetOptions.displayMode);
        SettingsActivity.selectString(this.metricSpinner, WidgetOptionCatalog.METRIC_VALUES, widgetOptionsLoadWidgetOptions.metricMode);
        Ui.addLabeledSpinner(linearLayoutCard, "Layout", this.styleSpinner, this.dark);
        Ui.addLabeledSpinner(linearLayoutCard, "Visual language", this.surfaceSpinner, this.dark);
        Ui.addLabeledSpinner(linearLayoutCard, "Ring and dial size", this.graphicSpinner, this.dark);
        Ui.addLabeledSpinner(linearLayoutCard, "Density", this.densitySpinner, this.dark);
        Ui.addLabeledSpinner(linearLayoutCard, "Theme", this.themeSpinner, this.dark);
        Ui.addLabeledSpinner(linearLayoutCard, "Accent", this.accentSpinner, this.dark);
        Ui.addLabeledSpinner(linearLayoutCard, "Background", this.opacitySpinner, this.dark);
        Ui.addLabeledSpinner(linearLayoutCard, "Reset time", this.resetSpinner, this.dark);
        Ui.addLabeledSpinner(linearLayoutCard, "Percentage", this.displaySpinner, this.dark);
        Ui.addLabeledSpinner(linearLayoutCard, "Usage windows", this.metricSpinner, this.dark);
        this.showTitle = Ui.checkbox(this, "Show Codex title", widgetOptionsLoadWidgetOptions.showTitle, this.dark);
        this.showPlan = Ui.checkbox(this, "Show plan label", widgetOptionsLoadWidgetOptions.showPlan, this.dark);
        this.showUpdated = Ui.checkbox(this, "Show updated time", widgetOptionsLoadWidgetOptions.showUpdated, this.dark);
        this.showRefresh = Ui.checkbox(this, "Show refresh control", widgetOptionsLoadWidgetOptions.showRefresh, this.dark);
        this.showResetCredits = Ui.checkbox(this, "Show reset-credit status", widgetOptionsLoadWidgetOptions.showResetCredits, this.dark);
        this.showResetAction = Ui.checkbox(this, "Show Use reset button", widgetOptionsLoadWidgetOptions.showResetAction, this.dark);
        linearLayoutCard.addView(this.showTitle);
        linearLayoutCard.addView(this.showPlan);
        linearLayoutCard.addView(this.showUpdated);
        linearLayoutCard.addView(this.showRefresh);
        linearLayoutCard.addView(this.showResetCredits);
        linearLayoutCard.addView(this.showResetAction);
        linearLayout.addView(linearLayoutCard);
        AdapterView.OnItemSelectedListener onItemSelectedListener = new AdapterView.OnItemSelectedListener() { // from class: dev.bennett.codexmeter.WidgetConfigActivity.2
            @Override // android.widget.AdapterView.OnItemSelectedListener
            public void onItemSelected(AdapterView<?> adapterView, View view, int i, long j) {
                WidgetConfigActivity.this.updatePreview();
            }

            @Override // android.widget.AdapterView.OnItemSelectedListener
            public void onNothingSelected(AdapterView<?> adapterView) {
            }
        };
        this.styleSpinner.setOnItemSelectedListener(onItemSelectedListener);
        this.densitySpinner.setOnItemSelectedListener(onItemSelectedListener);
        this.surfaceSpinner.setOnItemSelectedListener(onItemSelectedListener);
        this.graphicSpinner.setOnItemSelectedListener(onItemSelectedListener);
        this.themeSpinner.setOnItemSelectedListener(onItemSelectedListener);
        this.accentSpinner.setOnItemSelectedListener(onItemSelectedListener);
        this.opacitySpinner.setOnItemSelectedListener(onItemSelectedListener);
        this.resetSpinner.setOnItemSelectedListener(onItemSelectedListener);
        this.displaySpinner.setOnItemSelectedListener(onItemSelectedListener);
        this.metricSpinner.setOnItemSelectedListener(onItemSelectedListener);
        this.showTitle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() { // from class: dev.bennett.codexmeter.WidgetConfigActivity.3
            @Override // android.widget.CompoundButton.OnCheckedChangeListener
            public void onCheckedChanged(CompoundButton compoundButton, boolean z) {
                WidgetConfigActivity.this.updatePreview();
            }
        });
        this.showPlan.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() { // from class: dev.bennett.codexmeter.WidgetConfigActivity.4
            @Override // android.widget.CompoundButton.OnCheckedChangeListener
            public void onCheckedChanged(CompoundButton compoundButton, boolean z) {
                WidgetConfigActivity.this.updatePreview();
            }
        });
        this.showUpdated.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() { // from class: dev.bennett.codexmeter.WidgetConfigActivity.5
            @Override // android.widget.CompoundButton.OnCheckedChangeListener
            public void onCheckedChanged(CompoundButton compoundButton, boolean z) {
                WidgetConfigActivity.this.updatePreview();
            }
        });
        this.showRefresh.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() { // from class: dev.bennett.codexmeter.WidgetConfigActivity.6
            @Override // android.widget.CompoundButton.OnCheckedChangeListener
            public void onCheckedChanged(CompoundButton compoundButton, boolean z) {
                WidgetConfigActivity.this.updatePreview();
            }
        });
        this.showResetCredits.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() { // from class: dev.bennett.codexmeter.WidgetConfigActivity.7
            @Override // android.widget.CompoundButton.OnCheckedChangeListener
            public void onCheckedChanged(CompoundButton compoundButton, boolean z) {
                WidgetConfigActivity.this.updatePreview();
            }
        });
        this.showResetAction.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() { // from class: dev.bennett.codexmeter.WidgetConfigActivity.8
            @Override // android.widget.CompoundButton.OnCheckedChangeListener
            public void onCheckedChanged(CompoundButton compoundButton, boolean z) {
                WidgetConfigActivity.this.updatePreview();
            }
        });
        View viewText2 = Ui.text(this, "Large and Maximum make rings or gauges fill wider widgets sooner. Fully transparent removes the card. On Samsung, the One UI 8+ visual language leaves framing and blur to One UI Home instead of drawing a second rounded rectangle.", 12.0f, Ui.secondaryText(this.dark));
        LinearLayout.LayoutParams layoutParams3 = new LinearLayout.LayoutParams(-1, -2);
        layoutParams3.setMargins(0, Ui.dp(this, 16.0f), 0, 0);
        linearLayout.addView(viewText2, layoutParams3);
        LinearLayout linearLayoutHorizontal2 = Ui.horizontal(this, 16);
        Button button = Ui.button(this, "Cancel", false, this.dark);
        button.setOnClickListener(new View.OnClickListener() { // from class: dev.bennett.codexmeter.WidgetConfigActivity.9
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                WidgetConfigActivity.this.finish();
            }
        });
        linearLayoutHorizontal2.addView(button, new LinearLayout.LayoutParams(0, Ui.dp(this, 54.0f), 1.0f));
        Button button2 = Ui.button(this, "Save widget", true, this.dark);
        LinearLayout.LayoutParams layoutParams4 = new LinearLayout.LayoutParams(0, Ui.dp(this, 54.0f), 1.0f);
        layoutParams4.setMargins(Ui.dp(this, 12.0f), 0, 0, 0);
        linearLayoutHorizontal2.addView(button2, layoutParams4);
        button2.setOnClickListener(new View.OnClickListener() { // from class: dev.bennett.codexmeter.WidgetConfigActivity.10
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                WidgetConfigActivity.this.save();
            }
        });
        LinearLayout.LayoutParams layoutParams5 = new LinearLayout.LayoutParams(-1, -2);
        layoutParams5.setMargins(0, Ui.dp(this, 26.0f), 0, 0);
        linearLayout.addView(linearLayoutHorizontal2, layoutParams5);
        updatePreview();
    }

    private WidgetOptions currentOptions() {
        return new WidgetOptions(WidgetOptionCatalog.STYLE_VALUES[this.styleSpinner.getSelectedItemPosition()], WidgetOptionCatalog.DENSITY_VALUES[this.densitySpinner.getSelectedItemPosition()], WidgetOptionCatalog.SURFACE_VALUES[this.surfaceSpinner.getSelectedItemPosition()], WidgetOptionCatalog.GRAPHIC_VALUES[this.graphicSpinner.getSelectedItemPosition()], WidgetOptionCatalog.THEME_VALUES[this.themeSpinner.getSelectedItemPosition()], WidgetOptionCatalog.ACCENT_VALUES[this.accentSpinner.getSelectedItemPosition()], WidgetOptionCatalog.OPACITY_VALUES[this.opacitySpinner.getSelectedItemPosition()], WidgetOptionCatalog.RESET_VALUES[this.resetSpinner.getSelectedItemPosition()], WidgetOptionCatalog.DISPLAY_VALUES[this.displaySpinner.getSelectedItemPosition()], WidgetOptionCatalog.METRIC_VALUES[this.metricSpinner.getSelectedItemPosition()], this.showTitle.isChecked(), this.showPlan.isChecked(), this.showUpdated.isChecked(), this.showRefresh.isChecked(), this.showResetCredits.isChecked(), this.showResetAction.isChecked());
    }

    public void updatePreview() {
        if (this.preview != null && this.styleSpinner != null && this.showTitle != null) {
            this.preview.setOptions(currentOptions());
        }
    }

    public void save() {
        AppPreferences.saveWidgetOptions(this, this.appWidgetId, currentOptions());
        WidgetRenderer.update(this, AppWidgetManager.getInstance(this), this.appWidgetId);
        setResult(-1, new Intent().putExtra("appWidgetId", this.appWidgetId));
        Toast.makeText(this, "Widget updated.", 0).show();
        finish();
    }
}
