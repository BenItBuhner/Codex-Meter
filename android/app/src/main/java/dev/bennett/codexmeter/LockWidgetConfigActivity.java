package dev.bennett.codexmeter;

import android.content.Intent;
import android.appwidget.AppWidgetManager;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import dev.oneuiproject.oneui.widget.RoundedLinearLayout;

/* JADX INFO: loaded from: classes.dex */
public final class LockWidgetConfigActivity extends AppCompatActivity {
    private int appWidgetId = 0;
    private boolean dark;
    private Spinner metricSpinner;
    private ImageView preview;
    private CheckBox showCountdown;
    private CheckBox showResetAction;
    private CheckBox showResetCredits;

    @Override // android.app.Activity
    protected void onCreate(Bundle bundle) {
        Ui.applySelectedTheme(this);
        super.onCreate(bundle);
        setResult(0);
        this.appWidgetId = getIntent().getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0);
        if (this.appWidgetId == 0) {
            Toast.makeText(this, "No lock-screen widget was selected.", 1).show();
            finish();
        } else {
            this.dark = Ui.isDark(this);
            build();
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    private void build() {
        Ui.ConfigPage page = Ui.installConfigPage(this, "Lock-screen widget");
        LinearLayout linearLayout = page.content;
        page.preview.setBackgroundColor(Ui.controlSurface(this, this.dark));
        this.preview = new ImageView(this);
        this.preview.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        this.preview.setPadding(Ui.dp(this, 28.0f), Ui.dp(this, 28.0f), Ui.dp(this, 28.0f), Ui.dp(this, 28.0f));
        page.preview.addView(this.preview, new FrameLayout.LayoutParams(-1, -1));

        linearLayout.addView(Ui.separator(this, "Content"));
        RoundedLinearLayout contentCard = Ui.seslCard(this, this.dark);
        LockWidgetOptions lockWidgetOptionsLoadLockWidgetOptions = AppPreferences.loadLockWidgetOptions(this, this.appWidgetId);
        this.metricSpinner = Ui.spinner(this, WidgetOptionCatalog.METRIC_LABELS, this.dark);
        WidgetOptionCatalog.selectString(this.metricSpinner, WidgetOptionCatalog.METRIC_VALUES, lockWidgetOptionsLoadLockWidgetOptions.metricMode);
        Ui.addLabeledSpinner(contentCard, "Usage windows", this.metricSpinner, this.dark);
        this.showCountdown = Ui.checkbox(this, "Show live time until reset", lockWidgetOptionsLoadLockWidgetOptions.showCountdown, this.dark);
        this.showResetCredits = Ui.checkbox(this, "Show reset-credit count", lockWidgetOptionsLoadLockWidgetOptions.showResetCredits, this.dark);
        this.showResetAction = Ui.checkbox(this, "Tap tile to open Use reset confirmation", lockWidgetOptionsLoadLockWidgetOptions.showResetAction, this.dark);
        contentCard.addView(this.showCountdown);
        contentCard.addView(this.showResetCredits);
        contentCard.addView(this.showResetAction);
        TextView textViewText = Ui.text(this, "A reset is never consumed directly from the lock screen. The tile opens a confirmation screen first.", 12.0f, Ui.secondaryText(this.dark));
        LinearLayout.LayoutParams layoutParams3 = new LinearLayout.LayoutParams(-1, -2);
        layoutParams3.setMargins(0, Ui.dp(this, 12.0f), 0, 0);
        contentCard.addView(textViewText, layoutParams3);
        linearLayout.addView(contentCard);

        AdapterView.OnItemSelectedListener previewSelectionListener = new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                LockWidgetConfigActivity.this.updatePreview();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        };
        this.metricSpinner.setOnItemSelectedListener(previewSelectionListener);
        CompoundButton.OnCheckedChangeListener previewCheckListener = new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                LockWidgetConfigActivity.this.updatePreview();
            }
        };
        this.showCountdown.setOnCheckedChangeListener(previewCheckListener);
        this.showResetCredits.setOnCheckedChangeListener(previewCheckListener);
        this.showResetAction.setOnCheckedChangeListener(previewCheckListener);

        page.cancel.setOnClickListener(new View.OnClickListener() {
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                LockWidgetConfigActivity.this.finish();
            }
        });
        page.save.setOnClickListener(new View.OnClickListener() {
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                LockWidgetConfigActivity.this.save();
            }
        });
        updatePreview();
    }

    private LockWidgetOptions currentOptions() {
        return new LockWidgetOptions(WidgetOptionCatalog.METRIC_VALUES[this.metricSpinner.getSelectedItemPosition()], this.showResetCredits.isChecked(), this.showResetAction.isChecked(), this.showCountdown.isChecked());
    }

    private void updatePreview() {
        if (this.preview == null || this.metricSpinner == null || this.showCountdown == null) {
            return;
        }
        this.preview.setImageBitmap(SamsungLockGraphics.render(this,
                SamsungLockWidgetSupport.Shape.WIDE, SamsungLockWidgetSupport.Style.DIALS,
                73, 44, true, 180, 82, currentOptions(), 2, this.dark));
    }

    public void save() {
        AppPreferences.saveLockWidgetOptions(this, this.appWidgetId, currentOptions());
        SamsungLockWidgetSupport.updateById(this, this.appWidgetId);
        setResult(-1, new Intent().putExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID, this.appWidgetId));
        Toast.makeText(this, "Lock-screen widget updated.", 0).show();
        finish();
    }
}
