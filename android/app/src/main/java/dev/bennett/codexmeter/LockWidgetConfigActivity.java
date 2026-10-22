package dev.bennett.codexmeter;

import android.content.Context;
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
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(DesignChrome.wrap(base));
    }

    private int appWidgetId = 0;
    private boolean dark;
    private ImageView preview;
    private CheckBox showCountdown;
    private CheckBox showResetAction;
    private CheckBox showResetCredits;
    private TextView metersHint;
    private final LinkedHashSet<String> selectedMeters = new LinkedHashSet<>();
    private List<UsageProvider> providers = new ArrayList<>();
    private final java.util.Map<UsageProvider, SwitchCompat> providerSwitches =
            new java.util.EnumMap<>(UsageProvider.class);
    /** 1 for the single-gauge widget, 2 for the wide ones. */
    private int slots = 2;

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
            this.slots = SamsungLockWidgetSupport.slotCount(this, this.appWidgetId);
            this.providers = new ArrayList<>(ProviderPreferences.getLockWidgetProviders(this,
                    this.appWidgetId, maxProviders()));
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

        linearLayout.addView(Ui.separator(this, this.slots > 1 ? "Providers" : "Provider"));
        RoundedLinearLayout providersCard = Ui.seslCard(this, this.dark);
        TextView providersHint = Ui.text(this, this.slots > 1
                        ? "Pick one provider for two of its meters, or two providers for one "
                                + "meter each."
                        : "This widget shows one provider.",
                13.0f, Ui.secondaryText(this.dark));
        providersHint.setPadding(0, 0, 0, Ui.dp(this, 8));
        providersCard.addView(providersHint);
        boolean firstProvider = true;
        for (UsageProvider provider : ProviderPreferences.getOrder(this)) {
            SwitchCompat toggle = new SwitchCompat(this);
            toggle.setChecked(this.providers.contains(provider));
            this.providerSwitches.put(provider, toggle);
            providersCard.addView(buildSwitchRow(provider.displayName, toggle, !firstProvider));
            firstProvider = false;
            toggle.setOnCheckedChangeListener((button, checked) -> onProviderToggled(provider,
                    checked));
        }
        linearLayout.addView(providersCard);

        linearLayout.addView(Ui.separator(this, "Meters"));
        RoundedLinearLayout metersCard = Ui.seslCard(this, this.dark);
        this.metersHint = Ui.text(this,
                "Lock widgets show up to 2 meters. Extra selections are ignored.",
                13.0f, Ui.secondaryText(this.dark));
        this.metersHint.setPadding(0, 0, 0, Ui.dp(this, 8));
        metersCard.addView(this.metersHint);
        UsageSnapshot snapshot = ProviderPreferences.loadSnapshot(this, this.providers.get(0));
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

        page.onCancel(this::finish);
        page.onSave(this::save);
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
        int capacity = this.slots > 1 ? WidgetMeters.lockSlotCapacity() : 1;
        String message = this.providers.size() > 1
                ? "With two providers, each shows the first meter it reports."
                : this.slots > 1 ? "Lock widgets show up to " + capacity + " meters."
                : "The gauge shows the first meter it can.";
        if (selected > capacity && this.slots > 1 && this.providers.size() <= 1) {
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

    private int maxProviders() {
        return this.slots > 1 ? WidgetProviders.MAX_LOCK_WIDE : 1;
    }

    private void onProviderToggled(UsageProvider provider, boolean checked) {
        List<UsageProvider> updated;
        if (this.slots <= 1 && checked) {
            // Single gauge: picking a provider replaces the current one.
            updated = new ArrayList<>();
            updated.add(provider);
        } else {
            updated = WidgetProviders.toggled(this.providers, provider, checked,
                    ProviderPreferences.getOrder(this), maxProviders());
        }
        if (updated.contains(provider) != checked) {
            if (checked) {
                Toast.makeText(this, "This widget shows up to " + maxProviders()
                        + " providers.", Toast.LENGTH_SHORT).show();
            }
        }
        this.providers = updated;
        for (java.util.Map.Entry<UsageProvider, SwitchCompat> entry
                : this.providerSwitches.entrySet()) {
            boolean on = this.providers.contains(entry.getKey());
            if (entry.getValue().isChecked() != on) {
                entry.getValue().setChecked(on);
            }
        }
        updateMetersHint();
        updatePreview();
    }

    private void updatePreview() {
        if (this.preview == null || this.showCountdown == null) {
            return;
        }
        this.preview.setImageBitmap(SamsungLockWidgetSupport.previewBitmap(this, this.slots,
                this.providers, currentOptions()));
    }

    public void save() {
        AppPreferences.saveLockWidgetOptions(this, this.appWidgetId, currentOptions());
        ProviderPreferences.setWidgetProviders(this, this.appWidgetId, this.providers);
        SamsungLockWidgetSupport.updateById(this, this.appWidgetId);
        setResult(RESULT_OK, new Intent().putExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID, this.appWidgetId));
        Toast.makeText(this, "Lock-screen widget updated.", Toast.LENGTH_SHORT).show();
        finish();
    }
}
