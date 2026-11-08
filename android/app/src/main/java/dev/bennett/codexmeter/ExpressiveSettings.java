package dev.bennett.codexmeter;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.widget.NestedScrollView;
import androidx.core.widget.TextViewCompat;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.shape.ShapeAppearanceModel;
import com.google.android.material.slider.Slider;
import dev.bennett.codexmeter.glass.GlassModel;
import dev.bennett.codexmeter.glass.GlassModel.Row;

/**
 * Settings in the Material 3 Expressive design. The pages' behavior is the app's own settings code,
 * exactly as Liquid Glass uses it ({@link GlassSettings}): the One UI preference fragment runs out
 * of sight and its preferences are the model. This draws them with Material views: a large title,
 * section labels, rows grouped into segmented cards, Material switches, sliders and dialogs.
 */
final class ExpressiveSettings {
    private final AppCompatActivity activity;
    private final String page;
    private final Context ctx;
    private final boolean dark;
    private final GlassSettings settings;
    private final LinearLayout list;
    private final View root;

    ExpressiveSettings(AppCompatActivity activity, String page, String title) {
        this.activity = activity;
        this.page = page;
        this.ctx = ExpressiveDashboard.themed(activity);
        this.dark = Ui.isDark(activity);
        this.settings = new GlassSettings(activity);

        FrameLayout frame = new FrameLayout(this.ctx);
        frame.setBackgroundColor(color(this.dark ? R.attr.colorSurface : R.attr.colorSurfaceContainer));
        // The page's preference fragment lives here, out of sight: its preferences are the model.
        FrameLayout fragments = new FrameLayout(activity);
        fragments.setId(R.id.glass_fragments);
        fragments.setVisibility(View.GONE);
        frame.addView(fragments, new FrameLayout.LayoutParams(1, 1));

        NestedScrollView scroll = new NestedScrollView(this.ctx);
        scroll.setClipToPadding(false);
        LinearLayout column = new LinearLayout(this.ctx);
        column.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        column.setPadding(pad, 0, pad, dp(32));
        scroll.addView(column, new FrameLayout.LayoutParams(-1, -2));
        frame.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        MaterialButton back = new MaterialButton(this.ctx, null,
                R.attr.materialIconButtonStyle);
        back.setIconResource(R.drawable.ic_m3_arrow_back);
        back.setIconTint(ColorStateList.valueOf(color(R.attr.colorOnSurface)));
        back.setContentDescription("Back");
        back.setOnClickListener(v -> activity.getOnBackPressedDispatcher().onBackPressed());
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2);
        bp.topMargin = dp(8);
        column.addView(back, bp);

        TextView heading = text(title, R.attr.textAppearanceDisplaySmallEmphasized, R.attr.colorOnSurface);
        ExpressiveDashboard.weight(heading, 560);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
        hp.setMargins(dp(8), dp(20), dp(8), dp(12));
        column.addView(heading, hp);

        this.list = new LinearLayout(this.ctx);
        this.list.setOrientation(LinearLayout.VERTICAL);
        column.addView(this.list, new LinearLayout.LayoutParams(-1, -2));

        this.root = frame;
        activity.setContentView(frame);
        WindowCompat.setDecorFitsSystemWindows(activity.getWindow(), false);
        activity.getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
        activity.getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);
        WindowCompat.getInsetsController(activity.getWindow(), frame).setAppearanceLightStatusBars(!this.dark);
        WindowCompat.getInsetsController(activity.getWindow(), frame).setAppearanceLightNavigationBars(!this.dark);
        ViewCompat.setOnApplyWindowInsetsListener(frame, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, 0);
            scroll.setPadding(0, 0, 0, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
    }

    /** Draws the page from its preferences; call again after anything changes. */
    void render() {
        GlassModel.Page model = this.settings.page(this.page);
        this.list.removeAllViews();
        if (model == null) {
            return;
        }
        for (GlassModel.Section section : model.sections) {
            if (section.header != null) {
                TextView label = text(section.header, R.attr.textAppearanceLabelLargeEmphasized, R.attr.colorPrimary);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.setMargins(dp(16), dp(20), dp(16), dp(8));
                this.list.addView(label, lp);
            } else if (this.list.getChildCount() > 0) {
                addGap(dp(14));
            }
            int n = section.rows.size();
            for (int i = 0; i < n; i++) {
                this.list.addView(row(section.rows.get(i), i == 0, i == n - 1));
            }
            if (section.footer != null) {
                TextView foot = text(section.footer, R.attr.textAppearanceBodySmall, R.attr.colorOnSurfaceVariant);
                LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1, -2);
                fp.setMargins(dp(16), dp(8), dp(16), 0);
                this.list.addView(foot, fp);
            }
        }
    }

    private void addGap(int h) {
        this.list.addView(new View(this.ctx), new LinearLayout.LayoutParams(-1, h));
    }

    /** One row in a segmented card: large corners at the group's ends, small ones inside. */
    private View row(Row r, boolean first, boolean last) {
        MaterialCardView card = new MaterialCardView(this.ctx);
        card.setCardElevation(0);
        card.setStrokeWidth(0);
        card.setCardBackgroundColor(color(this.dark ? R.attr.colorSurfaceContainerHigh
                : R.attr.colorSurfaceContainerLowest));
        float big = dp(24);
        float small = dp(6);
        card.setShapeAppearanceModel(ShapeAppearanceModel.builder()
                .setTopLeftCornerSize(first ? big : small).setTopRightCornerSize(first ? big : small)
                .setBottomLeftCornerSize(last ? big : small).setBottomRightCornerSize(last ? big : small)
                .build());
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
        cp.bottomMargin = last ? 0 : dp(2);
        card.setLayoutParams(cp);

        LinearLayout content = new LinearLayout(this.ctx);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(16), dp(16), dp(16));
        card.addView(content, new FrameLayout.LayoutParams(-1, -2));
        LinearLayout line = new LinearLayout(this.ctx);
        line.setGravity(Gravity.CENTER_VERTICAL);
        content.addView(line, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout texts = new LinearLayout(this.ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        line.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        if (r.title != null && !r.title.isEmpty()) {
            texts.addView(text(r.title, R.attr.textAppearanceBodyLarge,
                    r.destructive ? R.attr.colorError : R.attr.colorOnSurface));
        }
        String summary = r.summary;
        if (r.kind == Row.Kind.CHOICE && r.entries != null && r.selected >= 0 && r.selected < r.entries.length) {
            summary = summary == null ? r.entries[r.selected] : r.entries[r.selected] + "\n" + summary;
        }
        if (r.kind == Row.Kind.FIELD) {
            summary = r.value == null || r.value.isEmpty() ? "Not set" : r.value;
        }
        if (summary != null && !summary.isEmpty()) {
            TextView s = text(summary, R.attr.textAppearanceBodyMedium,
                    r.kind == Row.Kind.CHOICE ? R.attr.colorPrimary : R.attr.colorOnSurfaceVariant);
            s.setPadding(0, dp(2), 0, 0);
            texts.addView(s);
        }

        switch (r.kind) {
            case SWITCH:
            case SWITCH_LINK: {
                MaterialSwitch sw = new MaterialSwitch(this.ctx);
                sw.setChecked(r.checked);
                sw.setEnabled(r.enabled);
                sw.setOnCheckedChangeListener((b, on) -> {
                    this.settings.toggle(this.page, r.key, on);
                    this.root.post(this::render);
                });
                LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2);
                sp.leftMargin = dp(12);
                line.addView(sw, sp);
                card.setOnClickListener(r.kind == Row.Kind.SWITCH_LINK
                        ? v -> this.settings.press(this.page, r.key)
                        : v -> sw.toggle());
                break;
            }
            case CHOICE:
                card.setOnClickListener(v -> choose(r));
                break;
            case SEGMENTED:
                segmented(content, r);
                break;
            case SLIDER: {
                Slider slider = new Slider(this.ctx);
                slider.setValueFrom(0f);
                slider.setValueTo(1f);
                slider.setValue((float) Math.max(0, Math.min(1, r.fraction)));
                slider.setEnabled(r.enabled);
                slider.setLabelBehavior(com.google.android.material.slider.LabelFormatter.LABEL_GONE);
                slider.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
                    @Override
                    public void onStartTrackingTouch(Slider s) {
                    }

                    @Override
                    public void onStopTrackingTouch(Slider s) {
                        if (r.onSlide != null) {
                            r.onSlide.on(s.getValue());
                        }
                    }
                });
                content.addView(slider, new LinearLayout.LayoutParams(-1, -2));
                break;
            }
            case FIELD:
                card.setOnClickListener(v -> edit(r));
                break;
            case LINK: {
                ImageView chevron = new ImageView(this.ctx);
                chevron.setImageResource(R.drawable.ic_m3_chevron_right);
                chevron.setImageTintList(ColorStateList.valueOf(color(R.attr.colorOnSurfaceVariant)));
                if (r.value != null) {
                    TextView v = text(r.value, R.attr.textAppearanceLabelLarge, R.attr.colorOnSurfaceVariant);
                    v.setPadding(dp(12), 0, dp(4), 0);
                    line.addView(v, new LinearLayout.LayoutParams(-2, -2));
                }
                line.addView(chevron, new LinearLayout.LayoutParams(dp(24), dp(24)));
                card.setOnClickListener(v -> this.settings.press(this.page, r.key));
                break;
            }
            case ACTION:
                card.setOnClickListener(v -> {
                    this.settings.press(this.page, r.key);
                    this.root.postDelayed(this::render, 300);
                });
                break;
            case INFO:
            default:
                break;
        }
        if (!r.enabled) {
            card.setAlpha(0.38f);
            card.setClickable(false);
        }
        return card;
    }

    private void choose(Row r) {
        if (r.entries == null) {
            return;
        }
        // The list is built here, not with setSingleChoiceItems: the activity's One UI base
        // theme styles the stock list rows, which made them white on white in light mode.
        LinearLayout list = new LinearLayout(this.ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(12), dp(4), dp(12), 0);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this.ctx);
        scroll.addView(list);
        androidx.appcompat.app.AlertDialog dialog =
                new MaterialAlertDialogBuilder(this.ctx, R.style.ThemeOverlay_Material3_MaterialAlertDialog)
                        .setTitle(r.title)
                        .setView(scroll)
                        .setNegativeButton("Cancel", null)
                        .create();
        int text = color(R.attr.colorOnSurface);
        android.content.res.ColorStateList tint = new android.content.res.ColorStateList(
                new int[][] {{android.R.attr.state_checked}, {}},
                new int[] {color(R.attr.colorPrimary), color(R.attr.colorOnSurfaceVariant)});
        for (int i = 0; i < r.entries.length; i++) {
            final int which = i;
            com.google.android.material.radiobutton.MaterialRadioButton b =
                    new com.google.android.material.radiobutton.MaterialRadioButton(this.ctx);
            b.setText(r.entries[i]);
            b.setTextColor(text);
            b.setButtonTintList(tint);
            b.setChecked(i == r.selected);
            b.setMinHeight(dp(48));
            b.setPadding(dp(12), 0, 0, 0);
            b.setOnClickListener(v -> {
                dialog.dismiss();
                this.settings.choose(this.page, r.key, which);
                this.root.post(this::render);
            });
            list.addView(b, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        dialog.show();
        tintButtons(dialog);
    }

    /** Dialog buttons in the design's primary color, not the One UI base theme's accent. */
    private void tintButtons(androidx.appcompat.app.AlertDialog dialog) {
        int primary = color(R.attr.colorPrimary);
        for (int id : new int[] {android.content.DialogInterface.BUTTON_POSITIVE,
                android.content.DialogInterface.BUTTON_NEGATIVE}) {
            android.widget.Button b = dialog.getButton(id);
            if (b != null) {
                b.setTextColor(primary);
            }
        }
    }

    private void edit(Row r) {
        EditText field = new EditText(this.ctx);
        field.setText(r.value == null ? "" : r.value);
        field.setHint(r.hint);
        field.setSingleLine(true);
        FrameLayout box = new FrameLayout(this.ctx);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        box.addView(field);
        field.setTextColor(color(R.attr.colorOnSurface));
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this.ctx, R.style.ThemeOverlay_Material3_MaterialAlertDialog)
                .setTitle(r.title)
                .setView(box)
                .setPositiveButton("Save", (d, which) -> {
                    if (r.onText != null) {
                        r.onText.on(field.getText().toString());
                    }
                    this.root.post(this::render);
                })
                .setNegativeButton("Cancel", null)
                .create();
        dialog.show();
        tintButtons(dialog);
    }

    /** A few short options as a connected button group (light, dark, system). */
    private void segmented(LinearLayout content, Row r) {
        if (r.entries == null) {
            return;
        }
        LinearLayout group = new LinearLayout(this.ctx);
        for (int i = 0; i < r.entries.length; i++) {
            boolean on = i == r.selected;
            MaterialButton b = new MaterialButton(this.ctx, null, on
                    ? R.attr.materialButtonStyle
                    : R.attr.materialButtonOutlinedStyle);
            b.setText(r.entries[i]);
            final int index = i;
            b.setOnClickListener(v -> {
                this.settings.choose(this.page, r.key, index);
                this.root.post(this::render);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            lp.leftMargin = i == 0 ? 0 : dp(6);
            group.addView(b, lp);
        }
        content.addView(group, new LinearLayout.LayoutParams(-1, -2));
    }

    private TextView text(String value, int appearance, int colorAttr) {
        TextView view = new TextView(this.ctx);
        android.util.TypedValue tv = new android.util.TypedValue();
        if (this.ctx.getTheme().resolveAttribute(appearance, tv, true)) {
            TextViewCompat.setTextAppearance(view, tv.resourceId);
        }
        view.setTextColor(color(colorAttr));
        view.setText(value == null ? "" : value);
        return view;
    }

    private int color(int attr) {
        return MaterialColors.getColor(this.ctx, attr, 0xFF808080);
    }

    private int dp(float v) {
        return Ui.dp(this.ctx, v);
    }
}
