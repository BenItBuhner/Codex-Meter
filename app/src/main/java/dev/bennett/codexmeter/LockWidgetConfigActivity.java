package dev.bennett.codexmeter;

import android.app.Activity;
import android.content.Intent;
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

/* JADX INFO: loaded from: classes.dex */
public final class LockWidgetConfigActivity extends Activity {
    private int appWidgetId = 0;
    private boolean dark;
    private Spinner metricSpinner;
    private CheckBox showCountdown;
    private CheckBox showResetAction;
    private CheckBox showResetCredits;

    @Override // android.app.Activity
    protected void onCreate(Bundle bundle) {
        Ui.applySelectedTheme(this);
        super.onCreate(bundle);
        setResult(0);
        this.appWidgetId = getIntent().getIntExtra("appWidgetId", 0);
        if (this.appWidgetId == 0) {
            Toast.makeText(this, "No lock-screen widget was selected.", 1).show();
            finish();
        } else {
            this.dark = Ui.isDark(this);
            build();
        }
    }

    private void build() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(Ui.background(this, this.dark));
        LinearLayout linearLayout = new LinearLayout(this);
        linearLayout.setOrientation(1);
        int iPageHorizontalPadding = Ui.pageHorizontalPadding(this);
        linearLayout.setPadding(iPageHorizontalPadding, Ui.pageTopPadding(this), iPageHorizontalPadding, Ui.dp(this, 38.0f));
        scrollView.addView(linearLayout, new FrameLayout.LayoutParams(-1, -2));
        setContentView(scrollView);
        Ui.configureSystemBars(this, scrollView, this.dark);
        LinearLayout linearLayoutHorizontal = Ui.horizontal(this, 16);
        Button buttonBackAction = Ui.backAction(this, this.dark);
        buttonBackAction.setOnClickListener(new View.OnClickListener() { // from class: dev.bennett.codexmeter.LockWidgetConfigActivity.1
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                LockWidgetConfigActivity.this.finish();
            }
        });
        linearLayoutHorizontal.addView(buttonBackAction, new LinearLayout.LayoutParams(Ui.dp(this, 48.0f), Ui.dp(this, 48.0f)));
        TextView textViewTitle = Ui.title(this, "Lock-screen widget", this.dark);
        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(0, -2, 1.0f);
        layoutParams.setMargins(Ui.dp(this, 12.0f), 0, 0, 0);
        linearLayoutHorizontal.addView(textViewTitle, layoutParams);
        linearLayout.addView(linearLayoutHorizontal);
        View viewText = Ui.text(this, "The visual style and square or wide size come from the lock-screen picker. These options control which allowance is shown and whether the tile exposes reset-credit status.", 13.0f, Ui.secondaryText(this.dark));
        LinearLayout.LayoutParams layoutParams2 = new LinearLayout.LayoutParams(-1, -2);
        layoutParams2.setMargins(0, Ui.dp(this, 8.0f), 0, 0);
        linearLayout.addView(viewText, layoutParams2);
        linearLayout.addView(Ui.sectionTitle(this, "Contents", this.dark));
        LinearLayout linearLayoutCard = Ui.card(this, this.dark);
        LockWidgetOptions lockWidgetOptionsLoadLockWidgetOptions = AppPreferences.loadLockWidgetOptions(this, this.appWidgetId);
        this.metricSpinner = Ui.spinner(this, WidgetOptionCatalog.METRIC_LABELS, this.dark);
        SettingsActivity.selectString(this.metricSpinner, WidgetOptionCatalog.METRIC_VALUES, lockWidgetOptionsLoadLockWidgetOptions.metricMode);
        Ui.addLabeledSpinner(linearLayoutCard, "Usage windows", this.metricSpinner, this.dark);
        this.showCountdown = Ui.checkbox(this, "Show live time until reset", lockWidgetOptionsLoadLockWidgetOptions.showCountdown, this.dark);
        this.showResetCredits = Ui.checkbox(this, "Show reset-credit count", lockWidgetOptionsLoadLockWidgetOptions.showResetCredits, this.dark);
        this.showResetAction = Ui.checkbox(this, "Tap tile to open Use reset confirmation", lockWidgetOptionsLoadLockWidgetOptions.showResetAction, this.dark);
        linearLayoutCard.addView(this.showCountdown);
        linearLayoutCard.addView(this.showResetCredits);
        linearLayoutCard.addView(this.showResetAction);
        TextView textViewText = Ui.text(this, "A reset is never consumed directly from the lock screen. The tile opens a confirmation screen first.", 12.0f, Ui.secondaryText(this.dark));
        LinearLayout.LayoutParams layoutParams3 = new LinearLayout.LayoutParams(-1, -2);
        layoutParams3.setMargins(0, Ui.dp(this, 12.0f), 0, 0);
        linearLayoutCard.addView(textViewText, layoutParams3);
        linearLayout.addView(linearLayoutCard);
        LinearLayout linearLayoutHorizontal2 = Ui.horizontal(this, 16);
        Button button = Ui.button(this, "Cancel", false, this.dark);
        button.setOnClickListener(new View.OnClickListener() { // from class: dev.bennett.codexmeter.LockWidgetConfigActivity.2
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                LockWidgetConfigActivity.this.finish();
            }
        });
        linearLayoutHorizontal2.addView(button, new LinearLayout.LayoutParams(0, Ui.dp(this, 54.0f), 1.0f));
        Button button2 = Ui.button(this, "Save tile", true, this.dark);
        LinearLayout.LayoutParams layoutParams4 = new LinearLayout.LayoutParams(0, Ui.dp(this, 54.0f), 1.0f);
        layoutParams4.setMargins(Ui.dp(this, 10.0f), 0, 0, 0);
        linearLayoutHorizontal2.addView(button2, layoutParams4);
        button2.setOnClickListener(new View.OnClickListener() { // from class: dev.bennett.codexmeter.LockWidgetConfigActivity.3
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                LockWidgetConfigActivity.this.save();
            }
        });
        LinearLayout.LayoutParams layoutParams5 = new LinearLayout.LayoutParams(-1, -2);
        layoutParams5.setMargins(0, Ui.dp(this, 24.0f), 0, 0);
        linearLayout.addView(linearLayoutHorizontal2, layoutParams5);
    }

    public void save() {
        AppPreferences.saveLockWidgetOptions(this, this.appWidgetId, new LockWidgetOptions(WidgetOptionCatalog.METRIC_VALUES[this.metricSpinner.getSelectedItemPosition()], this.showResetCredits.isChecked(), this.showResetAction.isChecked(), this.showCountdown.isChecked()));
        SamsungLockWidgetSupport.updateById(this, this.appWidgetId);
        setResult(-1, new Intent().putExtra("appWidgetId", this.appWidgetId));
        Toast.makeText(this, "Lock-screen widget updated.", 0).show();
        finish();
    }
}
