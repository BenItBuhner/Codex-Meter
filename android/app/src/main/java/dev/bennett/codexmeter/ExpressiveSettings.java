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
 * A page in the Material 3 Expressive design: Settings and every other page (usage history, reset
 * credits, updates, About, dashboard tabs, Edit dashboard, release history, lock screen widgets).
 * The pages' behavior is the app's own code, exactly as Liquid Glass uses it: Settings come from
 * the One UI preference fragment running out of sight ({@link GlassSettings}), the other pages from
 * {@link GlassScreens}. This draws them with Material views: a large title, section labels, rows
 * grouped into segmented cards, Material switches, sliders, dialogs, and the dashboard's cards
 * ({@link ExpressiveCards}) for pages that show charts and lists.
 */
final class ExpressiveSettings {
    /** Where a page's rows and actions come from. */
    interface Model {
        GlassModel.Page page();

        /** A switch on a settings preference (rows with their own {@code onToggle} use that). */
        void toggle(String key, boolean on);

        void choose(String key, int index);

        void press(String key);
    }

    private final AppCompatActivity activity;
    private final Model model;
    private final Context ctx;
    private final boolean dark;
    private final LinearLayout list;
    private final LinearLayout actions;
    private final TextView heading;
    private final View root;
    private final ExpressiveCards look;

    /** A Settings page, drawn from its preferences. */
    ExpressiveSettings(AppCompatActivity activity, String page, String title) {
        this(activity, title, settingsModel(new GlassSettings(activity), page));
    }

    private static Model settingsModel(GlassSettings settings, String page) {
        return new Model() {
            @Override
            public GlassModel.Page page() {
                return settings.page(page);
            }

            @Override
            public void toggle(String key, boolean on) {
                settings.toggle(page, key, on);
            }

            @Override
            public void choose(String key, int index) {
                settings.choose(page, key, index);
            }

            @Override
            public void press(String key) {
                settings.press(page, key);
            }
        };
    }

  // Android 15 draws apps edge to edge and ignores bar colors; the minSdk 26-34 devices still need them.
  @SuppressWarnings("deprecation")
    ExpressiveSettings(AppCompatActivity activity, String title, Model model) {
        this.activity = activity;
        this.model = model;
        this.ctx = ExpressiveDashboard.themed(activity);
        this.dark = Ui.isDark(activity);

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
        // Back on the left, the page's own actions (Customize, Edit…) on the right.
        LinearLayout bar = new LinearLayout(this.ctx);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(back, new LinearLayout.LayoutParams(-2, -2));
        bar.addView(new View(this.ctx), new LinearLayout.LayoutParams(0, 1, 1f));
        this.actions = new LinearLayout(this.ctx);
        this.actions.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(this.actions, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
        bp.topMargin = dp(8);
        column.addView(bar, bp);

        this.heading = text(title, R.attr.textAppearanceDisplaySmallEmphasized, R.attr.colorOnSurface);
        ExpressiveCards.weight(this.heading, 560);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
        hp.setMargins(dp(8), dp(20), dp(8), dp(12));
        column.addView(this.heading, hp);

        this.list = new LinearLayout(this.ctx);
        this.list.setOrientation(LinearLayout.VERTICAL);
        column.addView(this.list, new LinearLayout.LayoutParams(-1, -2));
        this.look = new ExpressiveCards(this.ctx, this.dark, this.list);

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
        // Rows read settings and the live monitor's state, and an action may change either later (a
        // dialog, a permission prompt, a service starting), so redraw when they change, not on a timer.
        frame.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View view) {
                AppPreferences.addChangeListener(view.getContext(), stateChanged);
                NowBarManager.addStateListener(view.getContext(), stateChanged);
            }

            @Override
            public void onViewDetachedFromWindow(View view) {
                AppPreferences.removeChangeListener(view.getContext(), stateChanged);
                NowBarManager.removeStateListener(view.getContext(), stateChanged);
                view.removeCallbacks(renderOnce);
            }
        });
    }

    private final Runnable renderOnce = this::render;

    /** Held in a field: SharedPreferences keeps listeners weakly. One redraw covers a burst of edits. */
    private final android.content.SharedPreferences.OnSharedPreferenceChangeListener stateChanged =
            (prefs, key) -> scheduleRender();

    private void scheduleRender() {
        this.root.removeCallbacks(this.renderOnce);
        this.root.post(this.renderOnce);
    }

    /** Draws the page from its model; call again after anything changes. */
    void render() {
        GlassModel.Page page = this.model.page();
        this.list.removeAllViews();
        this.actions.removeAllViews();
        if (page == null) {
            return;
        }
        if (page.title != null && !page.title.isEmpty()) {
            this.heading.setText(page.title);
        }
        for (GlassModel.Action a : page.actions) {
            MaterialButton action = new MaterialButton(this.ctx, null,
                    a.prominent ? R.attr.materialButtonStyle : R.attr.materialButtonTonalStyle);
            action.setText(a.label);
            action.setOnClickListener(v -> {
                if (a.onPress != null) {
                    a.onPress.run();
                }
                this.root.post(this::render);
            });
            LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-2, -2);
            ap.leftMargin = dp(8);
            this.actions.addView(action, ap);
        }
        if (page.intro != null && !page.intro.isEmpty()) {
            TextView intro = text(page.intro, R.attr.textAppearanceBodyLarge, R.attr.colorOnSurfaceVariant);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(-1, -2);
            ip.setMargins(dp(8), 0, dp(8), dp(12));
            this.list.addView(intro, ip);
        }
        for (GlassModel.Section section : page.sections) {
            if (section.header != null) {
                TextView label = text(section.header, R.attr.textAppearanceLabelLargeEmphasized, R.attr.colorPrimary);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.setMargins(dp(16), dp(20), dp(16), dp(8));
                this.list.addView(label, lp);
            } else if (this.list.getChildCount() > 0) {
                addGap(dp(14));
            }
            for (int i = 0; i < section.cards.size(); i++) {
                GlassModel.Card c = section.cards.get(i);
                if (c.kind == GlassModel.Card.Kind.HEADING) {
                    this.list.addView(this.look.heading(c.title));
                } else {
                    this.list.addView(this.look.card(c, false, false));
                }
            }
            int n = section.rows.size();
            for (int i = 0; i < n; i++) {
                View row = row(section.rows.get(i), i == 0, i == n - 1);
                if (section.onMove != null && n > 1) {
                    row = movable(row, section.onMove, i, n);
                }
                this.list.addView(row);
            }
            if (section.footer != null) {
                TextView foot = text(section.footer, R.attr.textAppearanceBodySmall, R.attr.colorOnSurfaceVariant);
                LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1, -2);
                fp.setMargins(dp(16), dp(8), dp(16), 0);
                this.list.addView(foot, fp);
            }
        }
    }

    /**
     * A row of a list that can be reordered (Dashboard tabs, Edit dashboard): Move up and Move down
     * buttons at its end, which also work with TalkBack.
     */
    private View movable(View row, GlassModel.Mover mover, int index, int count) {
        if (!(row instanceof MaterialCardView)) {
            return row;
        }
        MaterialCardView card = (MaterialCardView) row;
        LinearLayout content = (LinearLayout) card.getChildAt(0);
        LinearLayout line = (LinearLayout) content.getChildAt(0);
        line.addView(moveButton(R.drawable.ic_m3_arrow_upward, "Move up", index > 0,
                () -> mover.move(index, index - 1)));
        line.addView(moveButton(R.drawable.ic_m3_arrow_downward, "Move down", index < count - 1,
                () -> mover.move(index, index + 1)));
        return card;
    }

    private View moveButton(int icon, String label, boolean enabled, Runnable move) {
        MaterialButton b = new MaterialButton(this.ctx, null, R.attr.materialIconButtonStyle);
        b.setIconResource(icon);
        b.setIconTint(ColorStateList.valueOf(color(R.attr.colorOnSurfaceVariant)));
        b.setContentDescription(label);
        b.setEnabled(enabled);
        b.setAlpha(enabled ? 1f : 0.3f);
        b.setOnClickListener(v -> {
            move.run();
            this.root.post(this::render);
        });
        return b;
    }

    /** Runs a row's own handler when it has one, else the model's (a settings preference). */
    private void press(Row r) {
        if (r.onPress != null) {
            r.onPress.run();
        } else {
            this.model.press(r.key);
        }
    }

    private void toggle(Row r, boolean on) {
        if (r.onToggle != null) {
            r.onToggle.on(on);
        } else {
            this.model.toggle(r.key, on);
        }
    }

    private void pick(Row r, int index) {
        if (r.onChoose != null) {
            r.onChoose.on(index);
        } else {
            this.model.choose(r.key, index);
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
                    AppHaptics.tick(b);
                    toggle(r, on);
                    this.root.post(this::render);
                });
                LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2);
                sp.leftMargin = dp(12);
                line.addView(sw, sp);
                card.setOnClickListener(r.kind == Row.Kind.SWITCH_LINK
                        ? v -> press(r)
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
                card.setOnClickListener(v -> press(r));
                break;
            }
            case ACTION:
                card.setOnClickListener(v -> {
                    press(r);
                    this.root.post(this::render);
                });
                break;
            case RADIO: {
                if (r.checked) {
                    ImageView check = new ImageView(this.ctx);
                    check.setImageResource(R.drawable.ic_m3_check);
                    check.setImageTintList(ColorStateList.valueOf(color(R.attr.colorPrimary)));
                    line.addView(check, new LinearLayout.LayoutParams(dp(24), dp(24)));
                }
                card.setOnClickListener(v -> {
                    press(r);
                    this.root.post(this::render);
                });
                break;
            }
            case BUTTON: {
                texts.removeAllViews();
                MaterialButton b = new MaterialButton(this.ctx);
                b.setText(r.title);
                if (r.destructive) {
                    b.setBackgroundTintList(ColorStateList.valueOf(color(R.attr.colorError)));
                    b.setTextColor(color(R.attr.colorOnError));
                }
                b.setEnabled(r.enabled);
                b.setOnClickListener(v -> {
                    press(r);
                    this.root.post(this::render);
                });
                texts.addView(b, new LinearLayout.LayoutParams(-1, dp(56)));
                break;
            }
            case IMAGE: {
                if (r.image != null) {
                    ImageView image = new ImageView(this.ctx);
                    image.setImageBitmap(android.graphics.BitmapFactory.decodeByteArray(r.image, 0, r.image.length));
                    image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    content.addView(image, new LinearLayout.LayoutParams(-1, dp((float) r.imageHeight)));
                }
                break;
            }
            case CODE: {
                TextView code = text(r.value, R.attr.textAppearanceDisplaySmall, R.attr.colorOnSurface);
                code.setGravity(Gravity.CENTER);
                code.setLetterSpacing(0.15f);
                LinearLayout.LayoutParams cp2 = new LinearLayout.LayoutParams(-1, -2);
                cp2.topMargin = dp(8);
                content.addView(code, cp2);
                break;
            }
            case TEXT:
            case INFO:
                if (r.value != null && !r.value.isEmpty()) {
                    TextView v = text(r.value, R.attr.textAppearanceLabelLarge, R.attr.colorOnSurfaceVariant);
                    v.setPadding(dp(12), 0, 0, 0);
                    line.addView(v, new LinearLayout.LayoutParams(-2, -2));
                }
                break;
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
                pick(r, which);
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
                pick(r, index);
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
