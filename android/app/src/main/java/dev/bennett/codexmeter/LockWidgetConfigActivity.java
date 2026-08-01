package dev.bennett.codexmeter;

import android.content.Intent;
import android.appwidget.AppWidgetManager;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import dev.oneuiproject.oneui.widget.RoundedLinearLayout;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public final class LockWidgetConfigActivity extends AppCompatActivity {
    private int appWidgetId = 0;
    private boolean dark;
    private ImageView preview;
    private CheckBox showCountdown;
    private CheckBox showResetAction;
    private CheckBox showResetCredits;
    private TextView metersHint;
    private final LinkedHashSet<String> selectedMeters = new LinkedHashSet<>();

    @Override
    protected void onCreate(Bundle bundle) {
        Ui.applySelectedTheme(this);
        super.onCreate(bundle);
        setResult(0);
        this.appWidgetId = getIntent().getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0);
        if (this.appWidgetId == 0) {
            Toast.makeText(this, "No lock-screen widget was selected.", Toast.LENGTH_LONG).show();
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
        this.preview.setPadding(Ui.dp(this, 28.0f), Ui.dp(this, 28.0f), Ui.dp(this, 28.0f),
                Ui.dp(this, 28.0f));
        page.preview.addView(this.preview, new FrameLayout.LayoutParams(-1, -1));

        LockWidgetOptions saved = AppPreferences.loadLockWidgetOptions(this, this.appWidgetId);
        this.selectedMeters.clear();
        for (String key : WidgetMeters.parse(saved.effectiveVisibleMeters())) {
            if (WidgetMeters.FIVE_HOUR.equals(key) || WidgetMeters.WEEKLY.equals(key)) {
                this.selectedMeters.add(key);
            }
        }
        if (this.selectedMeters.isEmpty()) {
            this.selectedMeters.add(WidgetMeters.FIVE_HOUR);
        }

        linearLayout.addView(Ui.separator(this, "Meters"));
        RoundedLinearLayout metersCard = Ui.seslCard(this, this.dark);
        this.metersHint = Ui.text(this,
                "Lock widgets show up to 2 meters. Extra selections are ignored.",
                13.0f, Ui.secondaryText(this.dark));
        this.metersHint.setPadding(0, 0, 0, Ui.dp(this, 8));
        metersCard.addView(this.metersHint);
        UsageSnapshot snapshot = AppPreferences.loadSnapshot(this);
        List<String> available = new ArrayList<>();
        for (String key : WidgetMeters.availableKeys(snapshot)) {
            if (WidgetMeters.FIVE_HOUR.equals(key) || WidgetMeters.WEEKLY.equals(key)) {
                available.add(key);
            }
        }
        // Prefer saved selection order so the first enabled meter stays primary.
        List<String> ordered = new ArrayList<>();
        for (String key : this.selectedMeters) {
            if (available.contains(key) && !ordered.contains(key)) {
                ordered.add(key);
            }
        }
        for (String key : available) {
            if (!ordered.contains(key)) {
                ordered.add(key);
            }
        }
        boolean first = true;
        for (String key : ordered) {
            SwitchCompat toggle = new SwitchCompat(this);
            toggle.setChecked(this.selectedMeters.contains(key));
            metersCard.addView(buildSwitchRow(WidgetMeters.configLabel(key, snapshot), toggle,
                    !first));
            first = false;
            toggle.setOnCheckedChangeListener((button, checked) -> {
                if (checked) {
                    this.selectedMeters.add(key);
                } else {
                    this.selectedMeters.remove(key);
                    if (this.selectedMeters.isEmpty()) {
                        this.selectedMeters.add(key);
                        button.setChecked(true);
                        return;
                    }
                }
                updateMetersHint();
                updatePreview();
            });
        }
        linearLayout.addView(metersCard);

        linearLayout.addView(Ui.separator(this, "Content"));
        RoundedLinearLayout contentCard = Ui.seslCard(this, this.dark);
        this.showCountdown = Ui.checkbox(this, "Show live time until reset", saved.showCountdown,
                this.dark);
        this.showResetCredits = Ui.checkbox(this, "Show reset-credit count", saved.showResetCredits,
                this.dark);
        this.showResetAction = Ui.checkbox(this, "Tap tile to open Use reset confirmation",
                saved.showResetAction, this.dark);
        contentCard.addView(this.showCountdown);
        contentCard.addView(this.showResetCredits);
        contentCard.addView(this.showResetAction);
        TextView textViewText = Ui.text(this,
                "A reset is never consumed directly from the lock screen. The tile opens a confirmation screen first.",
                12.0f, Ui.secondaryText(this.dark));
        LinearLayout.LayoutParams layoutParams3 = new LinearLayout.LayoutParams(-1, -2);
        layoutParams3.setMargins(0, Ui.dp(this, 12.0f), 0, 0);
        contentCard.addView(textViewText, layoutParams3);
        linearLayout.addView(contentCard);

        CompoundButton.OnCheckedChangeListener previewCheckListener =
                (buttonView, isChecked) -> updatePreview();
        this.showCountdown.setOnCheckedChangeListener(previewCheckListener);
        this.showResetCredits.setOnCheckedChangeListener(previewCheckListener);
        this.showResetAction.setOnCheckedChangeListener(previewCheckListener);

        page.cancel.setOnClickListener(view -> finish());
        page.save.setOnClickListener(view -> save());
        updateMetersHint();
        updatePreview();
    }

    private LinearLayout buildSwitchRow(String title, SwitchCompat toggle, boolean topDivider) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        if (topDivider) {
            View divider = new View(this);
            divider.setBackgroundColor(Ui.divider(this.dark));
            LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(-1, Ui.dp(this, 1));
            dividerParams.setMargins(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
            row.addView(divider, dividerParams);
        }
        LinearLayout content = new LinearLayout(this);
        content.setGravity(Gravity.CENTER_VERTICAL);
        content.setMinimumHeight(Ui.dp(this, 52));
        content.addView(Ui.text(this, title, 16, Ui.mainText(this.dark)),
                new LinearLayout.LayoutParams(0, -2, 1));
        content.addView(toggle, new LinearLayout.LayoutParams(-2, -2));
        content.setOnClickListener(view -> toggle.toggle());
        row.addView(content, new LinearLayout.LayoutParams(-1, -2));
        return row;
    }

    private void updateMetersHint() {
        if (this.metersHint == null) {
            return;
        }
        int selected = this.selectedMeters.size();
        int capacity = WidgetMeters.lockSlotCapacity();
        String message = "Lock widgets show up to " + capacity + " meters.";
        if (selected > capacity) {
            message += " " + (selected - capacity)
                    + " extra selection" + (selected - capacity == 1 ? " is" : "s are")
                    + " ignored until you deselect others.";
        }
        this.metersHint.setText(message);
    }

    private LockWidgetOptions currentOptions() {
        List<String> ordered = new ArrayList<>();
        for (String key : this.selectedMeters) {
            if (WidgetMeters.FIVE_HOUR.equals(key) || WidgetMeters.WEEKLY.equals(key)) {
                ordered.add(key);
            }
        }
        String visible = WidgetMeters.serialize(ordered);
        boolean five = this.selectedMeters.contains(WidgetMeters.FIVE_HOUR);
        boolean weekly = this.selectedMeters.contains(WidgetMeters.WEEKLY);
        String metricMode = WidgetOptions.METRIC_BOTH;
        if (five && !weekly && this.selectedMeters.size() == 1) {
            metricMode = WidgetOptions.METRIC_FIVE_HOUR;
        } else if (weekly && !five && this.selectedMeters.size() == 1) {
            metricMode = WidgetOptions.METRIC_WEEKLY;
        }
        return new LockWidgetOptions(metricMode, this.showResetCredits.isChecked(),
                this.showResetAction.isChecked(), this.showCountdown.isChecked(), visible);
    }

    private void updatePreview() {
        if (this.preview == null || this.showCountdown == null) {
            return;
        }
        LockWidgetOptions options = currentOptions();
        UsageSnapshot snapshot = AppPreferences.loadSnapshot(this);
        List<String> available = WidgetMeters.availableKeys(snapshot);
        List<String> visible = WidgetMeters.cap(
                WidgetMeters.resolveVisibleForWidget(options.effectiveVisibleMeters(), available,
                        options.metricMode),
                WidgetMeters.lockSlotCapacity());
        int primary = 73;
        int secondary = -1;
        int primaryIcon = R.drawable.ic_oui_time;
        int secondaryIcon = R.drawable.ic_oui_calendar_week;
        if (!visible.isEmpty()) {
            primary = previewRemaining(visible.get(0), snapshot, 73);
            primaryIcon = previewIcon(visible.get(0));
        }
        if (visible.size() > 1) {
            secondary = previewRemaining(visible.get(1), snapshot, 44);
            secondaryIcon = previewIcon(visible.get(1));
        }
        this.preview.setImageBitmap(SamsungLockGraphics.render(this,
                SamsungLockWidgetSupport.Shape.WIDE, SamsungLockWidgetSupport.Style.DIALS,
                primary, secondary, true, 180, 82, options, 2,
                primaryIcon, secondaryIcon));
    }

    private static int previewIcon(String key) {
        if (WidgetMeters.WEEKLY.equals(key)
                || (WidgetMeters.isLimitKey(key) && !WidgetMeters.isLimitPrimary(key))) {
            return R.drawable.ic_oui_calendar_week;
        }
        return R.drawable.ic_oui_time;
    }

    private static int previewRemaining(String key, UsageSnapshot snapshot, int fallback) {
        if (WidgetMeters.FIVE_HOUR.equals(key) && snapshot != null && snapshot.fiveHour != null) {
            return snapshot.fiveHour.remainingPercent();
        }
        if (WidgetMeters.WEEKLY.equals(key) && snapshot != null && snapshot.weekly != null) {
            return snapshot.weekly.remainingPercent();
        }
        UsageLimit limit = WidgetMeters.findLimit(key, snapshot);
        if (limit != null) {
            UsageWindow window = WidgetMeters.isLimitPrimary(key) ? limit.primary : limit.secondary;
            if (window != null) {
                return window.remainingPercent();
            }
        }
        return fallback;
    }

    public void save() {
        AppPreferences.saveLockWidgetOptions(this, this.appWidgetId, currentOptions());
        SamsungLockWidgetSupport.updateById(this, this.appWidgetId);
        setResult(RESULT_OK, new Intent().putExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID, this.appWidgetId));
        Toast.makeText(this, "Lock-screen widget updated.", Toast.LENGTH_SHORT).show();
        finish();
    }
}
