package dev.bennett.codexmeter;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListPopupWindow;
import android.widget.ImageView;
import android.widget.RemoteViews;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.SeslSeekBar;
import dev.oneuiproject.oneui.widget.CardItemView;
import dev.oneuiproject.oneui.widget.RadioItemView;
import dev.oneuiproject.oneui.widget.RadioItemViewGroup;
import dev.oneuiproject.oneui.widget.RoundedLinearLayout;

public final class WidgetConfigActivity extends AppCompatActivity {
    private Spinner accentSpinner;
    private CardItemView accentRow;
    private int appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
    private boolean dark;
    private Spinner displaySpinner;
    private CardItemView displayRow;
    private SeslSeekBar opacitySlider;
    private SwitchCompat percentSymbolSwitch;
    private View opacityControl;
    private FrameLayout previewContainer;
    private Bundle widgetSize = new Bundle();
    private Spinner themeSpinner;
    private CardItemView themeRow;
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

        content.addView(Ui.separator(this, "Appearance"));
        RoundedLinearLayout appearanceCard = Ui.seslRowCard(this, this.dark);
        this.opacityControl = LayoutInflater.from(this).inflate(
                R.layout.view_widget_opacity, appearanceCard, false);
        appearanceCard.addView(this.opacityControl);
        this.opacitySlider = this.opacityControl.findViewById(R.id.opacity_slider);
        this.opacitySlider.setProgress(opacityIndex(saved.opacity));
        this.opacitySlider.setAlpha(0.0f);
        if (this.opacitySlider.getProgressDrawable() != null) {
            this.opacitySlider.getProgressDrawable().setAlpha(0);
        }

        this.themeSpinner = Ui.spinner(this, WidgetOptionCatalog.THEME_LABELS, this.dark);
        this.accentSpinner = Ui.spinner(this, WidgetOptionCatalog.ACCENT_LABELS, this.dark);
        WidgetOptionCatalog.selectString(this.themeSpinner, WidgetOptionCatalog.THEME_VALUES,
                saved.theme);
        WidgetOptionCatalog.selectString(this.accentSpinner, WidgetOptionCatalog.ACCENT_VALUES,
                saved.accent);
        this.themeRow = addOptionRow(appearanceCard, "Theme", this.themeSpinner,
                WidgetOptionCatalog.THEME_LABELS, true);
        this.accentRow = addOptionRow(appearanceCard, "Accent", this.accentSpinner,
                WidgetOptionCatalog.ACCENT_LABELS, true);
        this.accentRow.setOnClickListener(view -> showAccentDialog());
        content.addView(appearanceCard);

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
        LinearLayout symbolRow = new LinearLayout(this);
        symbolRow.setGravity(Gravity.CENTER_VERTICAL);
        symbolRow.setMinimumHeight(Ui.dp(this, 64));
        symbolRow.setPadding(Ui.dp(this, 20), 0, Ui.dp(this, 20), 0);
        symbolRow.addView(Ui.text(this, "Show % symbol", 18, Ui.mainText(this.dark)),
                new LinearLayout.LayoutParams(0, -2, 1));
        symbolRow.addView(this.percentSymbolSwitch,
                new LinearLayout.LayoutParams(-2, -2));
        symbolRow.setOnClickListener(view -> this.percentSymbolSwitch.toggle());
        contentCard.addView(symbolRow);
        content.addView(contentCard);

        content.addView(Ui.separator(this, "Widget tap action"));
        content.addView(buildTapActionCard());

        AdapterView.OnItemSelectedListener selectionListener = new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateRowSummaries();
                renderPreview();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        };
        this.themeSpinner.setOnItemSelectedListener(selectionListener);
        this.accentSpinner.setOnItemSelectedListener(selectionListener);
        this.displaySpinner.setOnItemSelectedListener(selectionListener);
        this.percentSymbolSwitch.setOnCheckedChangeListener((button, checked) -> renderPreview());
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
        row.setOnClickListener(view -> showDropDown(row, spinner, labels));
        card.addView(row);
        return row;
    }

    private void showDropDown(View anchor, Spinner spinner, String[] labels) {
        ArrayAdapter<String> adapter = checkAdapter(spinner, labels);
        ListPopupWindow popup = new ListPopupWindow(this);
        popup.setAdapter(adapter);
        popup.setAnchorView(anchor);
        popup.setWidth(Ui.dp(this, 240.0f));
        popup.setHeight(ListPopupWindow.WRAP_CONTENT);
        popup.setModal(true);
        popup.setOnItemClickListener((parent, view, position, id) -> {
            applyPreviewSelection(spinner, position);
            popup.dismiss();
        });
        popup.show();
    }

    private void showAccentDialog() {
        ArrayAdapter<String> adapter = checkAdapter(this.accentSpinner,
                WidgetOptionCatalog.ACCENT_LABELS);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Accent")
                .setAdapter(adapter, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getListView().setOnItemClickListener(
                (parent, view, position, id) -> {
                    applyPreviewSelection(this.accentSpinner, position);
                    dialog.dismiss();
                }));
        dialog.show();
    }

    private ArrayAdapter<String> checkAdapter(Spinner spinner, String[] labels) {
        return new ArrayAdapter<>(this,
                R.layout.widget_popup_check_item, R.id.popup_item_text, labels) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View row = convertView;
                if (row == null) {
                    row = LayoutInflater.from(WidgetConfigActivity.this).inflate(
                            R.layout.widget_popup_check_item, parent, false);
                }
                TextView text = row.findViewById(R.id.popup_item_text);
                ImageView check = row.findViewById(R.id.popup_item_check);
                text.setText(labels[position]);
                check.setImageTintList(null);
                check.setImageDrawable(new AccentCheckDrawable(
                        Ui.accent(WidgetConfigActivity.this, dark),
                        Ui.dp(WidgetConfigActivity.this, 24.0f)));
                check.setVisibility(position == spinner.getSelectedItemPosition()
                        ? View.VISIBLE : View.INVISIBLE);
                return row;
            }
        };
    }

    private static final class AccentCheckDrawable extends android.graphics.drawable.Drawable {
        private final android.graphics.Paint paint = new android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final int accent;
        private final int size;

        AccentCheckDrawable(int accent, int size) {
            this.accent = accent;
            this.size = size;
        }

        @Override
        public void draw(android.graphics.Canvas canvas) {
            float scale = size / 24.0f;
            float left = getBounds().left;
            float top = getBounds().top;
            paint.setStyle(android.graphics.Paint.Style.FILL);
            paint.setColor(accent);
            canvas.drawCircle(left + (12.0f * scale), top + (12.0f * scale),
                    12.0f * scale, paint);
            paint.setStyle(android.graphics.Paint.Style.STROKE);
            paint.setStrokeWidth(2.4f * scale);
            paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            paint.setStrokeJoin(android.graphics.Paint.Join.ROUND);
            paint.setColor(android.graphics.Color.WHITE);
            android.graphics.Path path = new android.graphics.Path();
            path.moveTo(left + (6.5f * scale), top + (12.2f * scale));
            path.lineTo(left + (10.2f * scale), top + (15.9f * scale));
            path.lineTo(left + (17.8f * scale), top + (8.3f * scale));
            canvas.drawPath(path, paint);
        }

        @Override public int getIntrinsicWidth() { return size; }
        @Override public int getIntrinsicHeight() { return size; }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(android.graphics.ColorFilter filter) {
            paint.setColorFilter(filter);
        }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    private void applyPreviewSelection(Spinner spinner, int position) {
        spinner.setSelection(position);
        updateRowSummaries();
        renderPreview();
    }

    private WidgetOptions currentOptions() {
        return new WidgetOptions(WidgetOptions.STYLE_RINGS,
                WidgetOptions.DENSITY_AUTO,
                AppPreferences.getAppStyle(this), WidgetOptions.GRAPHIC_AUTO,
                WidgetOptionCatalog.THEME_VALUES[this.themeSpinner.getSelectedItemPosition()],
                WidgetOptionCatalog.ACCENT_VALUES[this.accentSpinner.getSelectedItemPosition()],
                WidgetOptionCatalog.OPACITY_VALUES[this.opacitySlider.getProgress()],
                WidgetOptions.RESET_HIDDEN,
                WidgetOptionCatalog.DISPLAY_VALUES[this.displaySpinner.getSelectedItemPosition()],
                WidgetOptions.METRIC_BOTH, false, false, false, false, false, false)
                .withPercentSymbol(this.percentSymbolSwitch == null
                        || this.percentSymbolSwitch.isChecked());
    }

    private void updateRowSummaries() {
        if (this.themeRow == null) {
            return;
        }
        this.themeRow.setSummary(WidgetOptionCatalog.THEME_LABELS[
                this.themeSpinner.getSelectedItemPosition()]);
        this.accentRow.setSummary(WidgetOptionCatalog.ACCENT_LABELS[
                this.accentSpinner.getSelectedItemPosition()]);
        this.displayRow.setSummary(WidgetOptionCatalog.DISPLAY_LABELS[
                this.displaySpinner.getSelectedItemPosition()]);
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
        int[] ticks = {R.id.opacity_tick_0, R.id.opacity_tick_1,
                R.id.opacity_tick_2, R.id.opacity_tick_3};
        int level = Math.max(0, Math.min(ticks.length - 1, this.opacitySlider.getProgress()));
        for (int i = 0; i < ticks.length; i++) {
            this.opacityControl.findViewById(ticks[i]).setAlpha(i == level ? 0.0f : 1.0f);
        }
        View thumb = this.opacityControl.findViewById(R.id.opacity_thumb_visual);
        android.graphics.drawable.GradientDrawable thumbBackground = new android.graphics.drawable.GradientDrawable();
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

    private static int opacityIndex(int opacity) {
        int best = 0;
        int distance = Integer.MAX_VALUE;
        for (int i = 0; i < WidgetOptionCatalog.OPACITY_VALUES.length; i++) {
            int candidate = Math.abs(WidgetOptionCatalog.OPACITY_VALUES[i] - opacity);
            if (candidate < distance) {
                best = i;
                distance = candidate;
            }
        }
        return best;
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
