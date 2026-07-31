package dev.bennett.codexmeter;

import android.annotation.SuppressLint;
import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.RemoteViews;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.SeslSeekBar;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import dev.oneuiproject.oneui.widget.CardItemView;
import dev.oneuiproject.oneui.widget.RadioItemView;
import dev.oneuiproject.oneui.widget.RadioItemViewGroup;
import dev.oneuiproject.oneui.widget.RoundedLinearLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

public final class WidgetConfigActivity extends AppCompatActivity {
    private Spinner accentSpinner;
    private CardItemView accentRow;
    private int appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
    private boolean dark;
    private Spinner displaySpinner;
    private CardItemView displayRow;
    private Spinner styleSpinner;
    private CardItemView styleRow;
    private SeslSeekBar opacitySlider;
    private SwitchCompat backgroundSwitch;
    private SwitchCompat percentSymbolSwitch;
    private View opacityControl;
    private View backgroundRow;
    private FrameLayout previewContainer;
    private Bundle widgetSize = new Bundle();
    private Spinner themeSpinner;
    private CardItemView themeRow;
    private TextView metersHint;
    private RecyclerView metersList;
    private MeterAdapter meterAdapter;
    private final List<String> meterOrder = new ArrayList<>();
    private final LinkedHashSet<String> selectedMeters = new LinkedHashSet<>();
    private String tapAction = WidgetOptions.TAP_OPEN_APP;
    private final int tapOpenId = View.generateViewId();
    private final int tapRefreshId = View.generateViewId();
    private final int tapResetId = View.generateViewId();

    @Override
    @SuppressLint("RestrictedApi")
    protected void onCreate(Bundle state) {
        Ui.applySelectedTheme(this);
        super.onCreate(state);
        setResult(RESULT_CANCELED);
        this.appWidgetId = getIntent().getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID);
        if (this.appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            Toast.makeText(this, "No widget was selected.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        this.dark = Ui.isDark(this);
        try {
            Bundle options = AppWidgetManager.getInstance(this).getAppWidgetOptions(this.appWidgetId);
            if (options != null) {
                this.widgetSize = new Bundle(options);
            }
        } catch (RuntimeException ignored) {
        }
        build();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    private void build() {
        Ui.ConfigPage page = Ui.installConfigPage(this, "Customize widget");
        LinearLayout content = page.content;
        this.previewContainer = page.preview;

        WidgetOptions saved = AppPreferences.loadWidgetOptions(this, this.appWidgetId);
        this.tapAction = AppPreferences.getWidgetTapAction(this, this.appWidgetId);
        UsageSnapshot snapshot = AppPreferences.loadSnapshot(this);
        loadMeterSelection(saved, snapshot);

        content.addView(Ui.separator(this, "Appearance"));
        RoundedLinearLayout appearanceCard = Ui.seslRowCard(this, this.dark);
        this.backgroundSwitch = new SwitchCompat(this);
        this.backgroundSwitch.setChecked(saved.opacity > 0);
        this.backgroundRow = buildSwitchRow(getString(R.string.widget_background),
                this.backgroundSwitch, false);
        appearanceCard.addView(this.backgroundRow);
        View opacityDivider = new View(this);
        opacityDivider.setBackgroundColor(Ui.divider(this.dark));
        LinearLayout.LayoutParams opacityDividerParams =
                new LinearLayout.LayoutParams(-1, Ui.dp(this, 1));
        opacityDividerParams.setMargins(Ui.dp(this, 16), 0, Ui.dp(this, 16), 0);
        appearanceCard.addView(opacityDivider, opacityDividerParams);
        this.opacityControl = LayoutInflater.from(this).inflate(
                R.layout.view_widget_opacity, appearanceCard, false);
        this.opacityControl.setTag(opacityDivider);
        appearanceCard.addView(this.opacityControl);
        this.opacitySlider = this.opacityControl.findViewById(R.id.opacity_slider);
        this.opacitySlider.setProgress(WidgetOptions.opacityIndex(saved.opacity));
        this.opacitySlider.setAlpha(0.0f);
        if (this.opacitySlider.getProgressDrawable() != null) {
            this.opacitySlider.getProgressDrawable().setAlpha(0);
        }
        applyBackgroundEnabled(this.backgroundSwitch.isChecked());

        this.themeSpinner = Ui.spinner(this, WidgetOptionCatalog.THEME_LABELS, this.dark);
        this.accentSpinner = Ui.spinner(this, WidgetOptionCatalog.ACCENT_LABELS, this.dark);
        this.styleSpinner = Ui.spinner(this, WidgetOptionCatalog.STYLE_LABELS, this.dark);
        WidgetOptionCatalog.selectString(this.themeSpinner, WidgetOptionCatalog.THEME_VALUES,
                saved.theme);
        WidgetOptionCatalog.selectString(this.accentSpinner, WidgetOptionCatalog.ACCENT_VALUES,
                saved.accent);
        WidgetOptionCatalog.selectString(this.styleSpinner, WidgetOptionCatalog.STYLE_VALUES,
                saved.layoutPreference());
        this.styleRow = addOptionRow(appearanceCard, "Layout", this.styleSpinner,
                WidgetOptionCatalog.STYLE_LABELS, true);
        this.themeRow = addOptionRow(appearanceCard, "Theme", this.themeSpinner,
                WidgetOptionCatalog.THEME_LABELS, true);
        this.accentRow = addOptionRow(appearanceCard, "Accent", this.accentSpinner,
                WidgetOptionCatalog.ACCENT_LABELS, true);
        content.addView(appearanceCard);

        content.addView(Ui.separator(this, "Meters"));
        RoundedLinearLayout metersCard = Ui.seslRowCard(this, this.dark);
        this.metersHint = Ui.text(this, "", 13, Ui.secondaryText(this.dark));
        this.metersHint.setPadding(Ui.dp(this, 20), Ui.dp(this, 12), Ui.dp(this, 20),
                Ui.dp(this, 4));
        metersCard.addView(this.metersHint);
        TextView reorderHint = Ui.text(this,
                "Drag handles to choose which meters fill slots first. Empty selected meters "
                        + "keep a blank dash on the widget.",
                12, Ui.secondaryText(this.dark));
        reorderHint.setPadding(Ui.dp(this, 20), 0, Ui.dp(this, 20), Ui.dp(this, 8));
        metersCard.addView(reorderHint);
        this.metersList = new RecyclerView(this);
        this.metersList.setLayoutManager(new LinearLayoutManager(this));
        this.metersList.setNestedScrollingEnabled(false);
        this.meterAdapter = new MeterAdapter(snapshot);
        this.metersList.setAdapter(this.meterAdapter);
        ItemTouchHelper touchHelper = new ItemTouchHelper(new MeterReorderCallback());
        touchHelper.attachToRecyclerView(this.metersList);
        this.meterAdapter.touchHelper = touchHelper;
        metersCard.addView(this.metersList, new LinearLayout.LayoutParams(-1, -2));
        content.addView(metersCard);

        content.addView(Ui.separator(this, "Content"));
        RoundedLinearLayout contentCard = Ui.seslRowCard(this, this.dark);
        this.displaySpinner = Ui.spinner(this, WidgetOptionCatalog.DISPLAY_LABELS, this.dark);
        WidgetOptionCatalog.selectString(this.displaySpinner, WidgetOptionCatalog.DISPLAY_VALUES,
                saved.displayMode);
        this.displayRow = addOptionRow(contentCard, "Percentage", this.displaySpinner,
                WidgetOptionCatalog.DISPLAY_LABELS, false);
        View symbolDivider = new View(this);
        symbolDivider.setBackgroundColor(Ui.divider(this.dark));
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(-1, Ui.dp(this, 1));
        dividerParams.setMargins(Ui.dp(this, 16), 0, Ui.dp(this, 16), 0);
        contentCard.addView(symbolDivider, dividerParams);
        this.percentSymbolSwitch = new SwitchCompat(this);
        this.percentSymbolSwitch.setChecked(saved.showPercentSymbol);
        contentCard.addView(buildSwitchRow("Show % symbol", this.percentSymbolSwitch, false));
        content.addView(contentCard);

        content.addView(Ui.separator(this, "Widget tap action"));
        content.addView(buildTapActionCard());

        AdapterView.OnItemSelectedListener selectionListener = new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateRowSummaries();
                updateMetersHint();
                renderPreview();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        };
        this.styleSpinner.setOnItemSelectedListener(selectionListener);
        this.themeSpinner.setOnItemSelectedListener(selectionListener);
        this.accentSpinner.setOnItemSelectedListener(selectionListener);
        this.displaySpinner.setOnItemSelectedListener(selectionListener);
        this.percentSymbolSwitch.setOnCheckedChangeListener((button, checked) -> renderPreview());
        this.backgroundSwitch.setOnCheckedChangeListener((button, checked) -> {
            applyBackgroundEnabled(checked);
            updateSliderVisuals();
            renderPreview();
        });
        this.opacitySlider.setOnSeekBarChangeListener(new SeslSeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeslSeekBar seekBar, int progress, boolean fromUser) {
                updateSliderVisuals();
                renderPreview();
            }

            @Override
            public void onStartTrackingTouch(SeslSeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeslSeekBar seekBar) {
            }
        });
        page.cancel.setOnClickListener(view -> finish());
        page.save.setOnClickListener(view -> save());
        updateSliderVisuals();
        updateRowSummaries();
        updateMetersHint();
        renderPreview();
    }

    private RoundedLinearLayout buildTapActionCard() {
        RoundedLinearLayout card = Ui.seslRowCard(this, this.dark);
        RadioItemViewGroup group = new RadioItemViewGroup(this);
        group.setOrientation(LinearLayout.VERTICAL);
        group.addView(radioRow(this.tapOpenId, "Open Codex Meter", false));
        group.addView(radioRow(this.tapRefreshId, "Refresh usage", true));
        group.addView(radioRow(this.tapResetId, "Use reset if available", true));
        card.addView(group);
        group.check(tapActionId(this.tapAction));
        group.setOnCheckedChangeListener((radioGroup, checkedId) -> {
            if (checkedId == this.tapRefreshId) {
                this.tapAction = WidgetOptions.TAP_REFRESH;
            } else if (checkedId == this.tapResetId) {
                this.tapAction = WidgetOptions.TAP_USE_RESET;
            } else {
                this.tapAction = WidgetOptions.TAP_OPEN_APP;
            }
        });
        return card;
    }

    private LinearLayout buildSwitchRow(String title, SwitchCompat toggle, boolean topDivider) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        if (topDivider) {
            View divider = new View(this);
            divider.setBackgroundColor(Ui.divider(this.dark));
            LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(-1, Ui.dp(this, 1));
            dividerParams.setMargins(Ui.dp(this, 16), 0, Ui.dp(this, 16), 0);
            row.addView(divider, dividerParams);
        }
        LinearLayout content = new LinearLayout(this);
        content.setGravity(Gravity.CENTER_VERTICAL);
        content.setMinimumHeight(Ui.dp(this, 64));
        content.setPadding(Ui.dp(this, 20), 0, Ui.dp(this, 20), 0);
        content.addView(Ui.text(this, title, 18, Ui.mainText(this.dark)),
                new LinearLayout.LayoutParams(0, -2, 1));
        content.addView(toggle, new LinearLayout.LayoutParams(-2, -2));
        content.setOnClickListener(view -> toggle.toggle());
        row.addView(content, new LinearLayout.LayoutParams(-1, -2));
        return row;
    }

    private void applyBackgroundEnabled(boolean enabled) {
        if (this.opacityControl == null) {
            return;
        }
        int visibility = enabled ? View.VISIBLE : View.GONE;
        this.opacityControl.setVisibility(visibility);
        Object divider = this.opacityControl.getTag();
        if (divider instanceof View) {
            ((View) divider).setVisibility(visibility);
        }
        if (this.opacitySlider != null) {
            this.opacitySlider.setEnabled(enabled);
        }
    }

    private RadioItemView radioRow(int id, String title, boolean divider) {
        RadioItemView row = new RadioItemView(this);
        row.setId(id);
        row.setTitle(title);
        row.setShowTopDivider(divider);
        return row;
    }

    private int tapActionId(String action) {
        if (WidgetOptions.TAP_REFRESH.equals(action)) {
            return this.tapRefreshId;
        }
        if (WidgetOptions.TAP_USE_RESET.equals(action)) {
            return this.tapResetId;
        }
        return this.tapOpenId;
    }

    private CardItemView addOptionRow(RoundedLinearLayout card, String title, Spinner spinner,
            String[] labels, boolean divider) {
        CardItemView row = new CardItemView(this);
        row.setTitle(title);
        row.setSummary(labels[Math.max(0, spinner.getSelectedItemPosition())]);
        row.setShowTopDivider(divider);
        row.setShowBottomDivider(false);
        row.setOnClickListener(view -> OneUiChoiceDialog.show(this, title, labels,
                spinner.getSelectedItemPosition(),
                position -> applyPreviewSelection(spinner, position)));
        card.addView(row);
        return row;
    }

    private void applyPreviewSelection(Spinner spinner, int position) {
        spinner.setSelection(position);
        updateRowSummaries();
        updateMetersHint();
        renderPreview();
    }

    private WidgetOptions currentOptions() {
        boolean backgroundOn = this.backgroundSwitch == null || this.backgroundSwitch.isChecked();
        int opacity = backgroundOn
                ? WidgetOptionCatalog.OPACITY_VALUES[Math.max(0, Math.min(
                        WidgetOptionCatalog.OPACITY_VALUES.length - 1,
                        this.opacitySlider.getProgress()))]
                : 0;
        String layout = WidgetOptionCatalog.STYLE_VALUES[
                Math.max(0, this.styleSpinner.getSelectedItemPosition())];
        return new WidgetOptions(layout,
                WidgetOptions.DENSITY_AUTO,
                WidgetOptions.SURFACE_ONE_UI, WidgetOptions.GRAPHIC_AUTO,
                WidgetOptionCatalog.THEME_VALUES[this.themeSpinner.getSelectedItemPosition()],
                WidgetOptionCatalog.ACCENT_VALUES[this.accentSpinner.getSelectedItemPosition()],
                opacity,
                WidgetOptions.RESET_HIDDEN,
                WidgetOptionCatalog.DISPLAY_VALUES[this.displaySpinner.getSelectedItemPosition()],
                WidgetOptions.METRIC_BOTH,
                false, false, false, false, false, false)
                .withPercentSymbol(this.percentSymbolSwitch == null
                        || this.percentSymbolSwitch.isChecked())
                .withVisibleMeters(WidgetMeters.serialize(orderedSelectedMeters()));
    }

    private void loadMeterSelection(WidgetOptions saved, UsageSnapshot snapshot) {
        List<String> available = WidgetMeters.availableKeys(snapshot);
        List<String> savedVisible = WidgetMeters.parse(saved.effectiveVisibleMeters());
        this.meterOrder.clear();
        this.selectedMeters.clear();
        for (String key : savedVisible) {
            if (available.contains(key) && !this.meterOrder.contains(key)) {
                this.meterOrder.add(key);
                this.selectedMeters.add(key);
            }
        }
        for (String key : available) {
            if (!this.meterOrder.contains(key)) {
                this.meterOrder.add(key);
            }
        }
        if (this.selectedMeters.isEmpty() && !this.meterOrder.isEmpty()) {
            this.selectedMeters.add(this.meterOrder.get(0));
        }
    }

    private List<String> orderedSelectedMeters() {
        List<String> ordered = new ArrayList<>();
        for (String key : this.meterOrder) {
            if (this.selectedMeters.contains(key)) {
                ordered.add(key);
            }
        }
        return ordered;
    }

    private void lockMeterListScrolling() {
        if (this.metersList != null && this.metersList.getParent() != null) {
            this.metersList.getParent().requestDisallowInterceptTouchEvent(true);
        }
    }

    private final class MeterAdapter extends RecyclerView.Adapter<MeterHolder> {
        ItemTouchHelper touchHelper;
        private final UsageSnapshot snapshot;

        MeterAdapter(UsageSnapshot snapshot) {
            this.snapshot = snapshot;
        }

        @Override
        public MeterHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            LinearLayout row = Ui.horizontal(WidgetConfigActivity.this, Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(Ui.dp(WidgetConfigActivity.this, 64));
            row.setPadding(Ui.dp(WidgetConfigActivity.this, 20),
                    Ui.dp(WidgetConfigActivity.this, 8),
                    Ui.dp(WidgetConfigActivity.this, 12),
                    Ui.dp(WidgetConfigActivity.this, 8));
            row.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));

            TextView title = Ui.text(WidgetConfigActivity.this, "", 17.0f,
                    Ui.mainText(WidgetConfigActivity.this.dark));
            row.addView(title, new LinearLayout.LayoutParams(0, -2, 1.0f));

            SwitchCompat toggle = new SwitchCompat(WidgetConfigActivity.this);
            toggle.setContentDescription("Show on widget");
            LinearLayout.LayoutParams toggleParams = new LinearLayout.LayoutParams(-2, -2);
            toggleParams.setMargins(Ui.dp(WidgetConfigActivity.this, 8), 0,
                    Ui.dp(WidgetConfigActivity.this, 4), 0);
            row.addView(toggle, toggleParams);

            ImageView handle = new ImageView(WidgetConfigActivity.this);
            handle.setImageResource(R.drawable.ic_oui_reorder);
            handle.setImageTintList(ColorStateList.valueOf(
                    Ui.secondaryText(WidgetConfigActivity.this.dark)));
            handle.setContentDescription("Reorder");
            int pad = Ui.dp(WidgetConfigActivity.this, 12);
            handle.setPadding(pad, pad, pad, pad);
            row.addView(handle, new LinearLayout.LayoutParams(
                    Ui.dp(WidgetConfigActivity.this, 48),
                    Ui.dp(WidgetConfigActivity.this, 48)));

            MeterHolder holder = new MeterHolder(row, title, toggle, handle);
            bindDragHandle(holder);
            return holder;
        }

        @SuppressLint("ClickableViewAccessibility")
        private void bindDragHandle(MeterHolder holder) {
            holder.handle.setOnTouchListener((view, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN && touchHelper != null) {
                    lockMeterListScrolling();
                    touchHelper.startDrag(holder);
                    return true;
                }
                return false;
            });
        }

        @Override
        public void onBindViewHolder(MeterHolder holder, int position) {
            String key = meterOrder.get(position);
            holder.title.setText(WidgetMeters.configLabel(key, snapshot));
            holder.toggle.setOnCheckedChangeListener(null);
            boolean visible = selectedMeters.contains(key);
            holder.toggle.setChecked(visible);
            applyRowVisibility(holder, visible);
            holder.toggle.setOnCheckedChangeListener((button, checked) -> {
                int pos = holder.getBindingAdapterPosition();
                if (pos < 0 || pos >= meterOrder.size()) {
                    return;
                }
                String rowKey = meterOrder.get(pos);
                if (checked) {
                    selectedMeters.add(rowKey);
                } else {
                    selectedMeters.remove(rowKey);
                    if (selectedMeters.isEmpty()) {
                        selectedMeters.add(rowKey);
                        button.setChecked(true);
                        return;
                    }
                }
                applyRowVisibility(holder, button.isChecked());
                updateMetersHint();
                renderPreview();
            });
        }

        private void applyRowVisibility(MeterHolder holder, boolean visible) {
            float alpha = visible ? 1.0f : 0.45f;
            holder.title.setAlpha(alpha);
        }

        @Override
        public int getItemCount() {
            return meterOrder.size();
        }
    }

    private static final class MeterHolder extends RecyclerView.ViewHolder {
        final TextView title;
        final SwitchCompat toggle;
        final ImageView handle;

        MeterHolder(View row, TextView title, SwitchCompat toggle, ImageView handle) {
            super(row);
            this.title = title;
            this.toggle = toggle;
            this.handle = handle;
        }
    }

    private final class MeterReorderCallback extends ItemTouchHelper.Callback {
        @Override
        public int getMovementFlags(RecyclerView recyclerView, RecyclerView.ViewHolder holder) {
            return makeMovementFlags(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0);
        }

        @Override
        public boolean isLongPressDragEnabled() {
            return true;
        }

        @Override
        public boolean onMove(RecyclerView recyclerView, RecyclerView.ViewHolder from,
                RecyclerView.ViewHolder to) {
            int fromPosition = from.getBindingAdapterPosition();
            int toPosition = to.getBindingAdapterPosition();
            if (fromPosition < 0 || toPosition < 0) {
                return false;
            }
            if (fromPosition < toPosition) {
                for (int i = fromPosition; i < toPosition; i++) {
                    Collections.swap(meterOrder, i, i + 1);
                }
            } else {
                for (int i = fromPosition; i > toPosition; i--) {
                    Collections.swap(meterOrder, i, i - 1);
                }
            }
            recyclerView.getAdapter().notifyItemMoved(fromPosition, toPosition);
            updateMetersHint();
            renderPreview();
            return true;
        }

        @Override
        public void onSelectedChanged(RecyclerView.ViewHolder holder, int actionState) {
            super.onSelectedChanged(holder, actionState);
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && holder != null) {
                lockMeterListScrolling();
                holder.itemView.setAlpha(0.85f);
                holder.itemView.setElevation(Ui.dp(holder.itemView.getContext(), 4));
            }
        }

        @Override
        public void clearView(RecyclerView recyclerView, RecyclerView.ViewHolder holder) {
            super.clearView(recyclerView, holder);
            holder.itemView.setAlpha(1.0f);
            holder.itemView.setElevation(0.0f);
            updateMetersHint();
            renderPreview();
        }

        @Override
        public void onSwiped(RecyclerView.ViewHolder holder, int direction) {
        }
    }

    private void updateRowSummaries() {
        if (this.themeRow == null) {
            return;
        }
        if (this.styleRow != null) {
            this.styleRow.setSummary(WidgetOptionCatalog.STYLE_LABELS[
                    this.styleSpinner.getSelectedItemPosition()]);
        }
        this.themeRow.setSummary(WidgetOptionCatalog.THEME_LABELS[
                this.themeSpinner.getSelectedItemPosition()]);
        this.accentRow.setSummary(WidgetOptionCatalog.ACCENT_LABELS[
                this.accentSpinner.getSelectedItemPosition()]);
        this.displayRow.setSummary(WidgetOptionCatalog.DISPLAY_LABELS[
                this.displaySpinner.getSelectedItemPosition()]);
    }

    private void updateMetersHint() {
        if (this.metersHint == null) {
            return;
        }
        WidgetOptions options = currentOptions();
        int height = positiveOption("appWidgetMinHeight", "appWidgetMaxHeight", 70);
        int width = positiveOption("appWidgetMinWidth", "appWidgetMaxWidth", 110);
        int rows = this.widgetSize.getInt("semAppWidgetRowSpan", 0);
        int columns = this.widgetSize.getInt("semAppWidgetColumnSpan", 0);
        String visual = WidgetMeters.resolveHomeVisualStyle(options.layoutPreference(),
                options.singleMetric(), rows, columns, height, width);
        int capacity = WidgetMeters.slotCapacity(visual, height);
        int selected = this.selectedMeters.size();
        String message = "This size shows up to " + capacity + " meter"
                + (capacity == 1 ? "" : "s") + ".";
        if (selected > capacity) {
            message += " " + (selected - capacity) + " extra selection"
                    + (selected - capacity == 1 ? " is" : "s are")
                    + " saved and appear when the widget is larger.";
        }
        this.metersHint.setText(message);
    }

    private void renderPreview() {
        if (this.previewContainer == null || this.opacitySlider == null) {
            return;
        }
        this.previewContainer.post(() -> {
            try {
                Bundle latestSize = AppWidgetManager.getInstance(this)
                        .getAppWidgetOptions(this.appWidgetId);
                if (latestSize != null && !latestSize.isEmpty()) {
                    this.widgetSize = new Bundle(latestSize);
                }
                WidgetOptions options = currentOptions();
                RemoteViews remote = WidgetRenderer.buildPreview(this, this.appWidgetId, options,
                        this.widgetSize);
                FrameLayout surface = new FrameLayout(this);
                surface.setClipToOutline(true);
                GradientDrawable background = new GradientDrawable();
                boolean previewDark = WidgetOptions.THEME_DARK.equals(options.theme)
                        || (!WidgetOptions.THEME_LIGHT.equals(options.theme) && this.dark);
                int alpha = Math.round(options.opacity * 2.55f);
                background.setColor(previewDark ? Color.argb(alpha, 0, 0, 0)
                        : Color.argb(alpha, 255, 255, 255));
                background.setCornerRadius(Ui.dp(this, 28.0f));
                surface.setBackground(background);
                // Use the application inflater so AppCompat does not substitute its ImageView;
                // RemoteViews reflection is intentionally restricted to framework widgets.
                View widget = remote.apply(getApplicationContext(), surface);
                widget.setClickable(false);
                surface.addView(widget, new FrameLayout.LayoutParams(-1, -1));
                surface.setOnClickListener(view -> { });

                int inset = Ui.dp(this, 14.0f);
                int maxWidth = Math.max(1, this.previewContainer.getWidth() - (2 * inset));
                int maxHeight = Math.max(1, this.previewContainer.getHeight() - (2 * inset));
                float aspect = previewAspectRatio();
                int columns = this.widgetSize.getInt("semAppWidgetColumnSpan", 0);
                int width = columns > 0
                        ? Math.max(1, Math.round(maxWidth * (Math.min(4, columns) / 4.0f)))
                        : maxWidth;
                int height = Math.max(1, Math.round(width / aspect));
                if (height > maxHeight) {
                    height = maxHeight;
                    width = Math.max(1, Math.round(height * aspect));
                }
                FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, height,
                        Gravity.CENTER);
                this.previewContainer.removeAllViews();
                ImageView backdrop = new ImageView(this);
                backdrop.setImageResource(R.drawable.codex_meter_icon_bg);
                backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
                backdrop.setContentDescription(null);
                this.previewContainer.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));
                this.previewContainer.addView(surface, params);
            } catch (RuntimeException exception) {
                Log.w("CodexMeterPreview", "Unable to render widget preview", exception);
            }
        });
    }

    private float previewAspectRatio() {
        int optionWidth = positiveOption("appWidgetMinWidth", "appWidgetMaxWidth", 0);
        int optionHeight = positiveOption("appWidgetMinHeight", "appWidgetMaxHeight", 0);
        if (optionWidth > 0 && optionHeight > 0) {
            return Math.max(0.45f, Math.min(5.0f, optionWidth / (float) optionHeight));
        }
        int columns = this.widgetSize.getInt("semAppWidgetColumnSpan", 0);
        int rows = this.widgetSize.getInt("semAppWidgetRowSpan", 0);
        if (columns > 0 && rows > 0) {
            return columns / (float) rows;
        }
        int width = positiveOption("appWidgetMinWidth", "appWidgetMaxWidth", 220);
        int height = positiveOption("appWidgetMinHeight", "appWidgetMaxHeight", 70);
        return Math.max(0.45f, Math.min(4.0f, width / (float) height));
    }

    private int positiveOption(String first, String second, int fallback) {
        int value = this.widgetSize.getInt(first, 0);
        if (value <= 0) {
            value = this.widgetSize.getInt(second, 0);
        }
        return value > 0 ? value : fallback;
    }

    private void updateSliderVisuals() {
        if (this.opacityControl == null || this.opacitySlider == null
                || this.opacityControl.getVisibility() != View.VISIBLE) {
            return;
        }
        int[] ticks = {R.id.opacity_tick_0, R.id.opacity_tick_1, R.id.opacity_tick_2};
        int level = Math.max(0, Math.min(ticks.length - 1, this.opacitySlider.getProgress()));
        for (int i = 0; i < ticks.length; i++) {
            this.opacityControl.findViewById(ticks[i]).setAlpha(i == level ? 0.0f : 1.0f);
        }
        View thumb = this.opacityControl.findViewById(R.id.opacity_thumb_visual);
        android.graphics.drawable.GradientDrawable thumbBackground =
                new android.graphics.drawable.GradientDrawable();
        thumbBackground.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        thumbBackground.setColor(dark ? 0xFF1F1F22 : 0xFFFCFCFF);
        thumbBackground.setStroke(Ui.dp(this, 2), Ui.accent(this, dark));
        thumb.setBackground(thumbBackground);
        thumb.post(() -> {
            View tick = this.opacityControl.findViewById(ticks[level]);
            thumb.setTranslationX(tick.getX() + (tick.getWidth() / 2.0f)
                    - (thumb.getWidth() / 2.0f));
        });
    }

    private void save() {
        AppPreferences.saveWidgetOptions(this, this.appWidgetId, currentOptions());
        AppPreferences.saveWidgetTapAction(this, this.appWidgetId, this.tapAction);
        WidgetRenderer.update(this, AppWidgetManager.getInstance(this), this.appWidgetId);
        setResult(RESULT_OK, new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,
                this.appWidgetId));
        Toast.makeText(this, "Widget updated.", Toast.LENGTH_SHORT).show();
        finish();
    }
}
