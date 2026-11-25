package dev.bennett.codexmeter;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.view.ViewCompat;
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
import java.util.ArrayList;
import java.util.List;

/**
 * Material 3 Expressive's grouped lists: a section's label, its cards and rows as one segmented
 * group (large corners only at the group's two ends, small ones between items), and its footer.
 * Settings pages ({@link ExpressiveSettings}) and the dashboard's Edit mode
 * ({@link ExpressiveDashboard}) draw their sections here, so both look and behave the same.
 *
 * <p>A list that can be reordered (Providers, a tab's cards, Priority's sections) gets a drag
 * handle on each row: the row lifts while held, its neighbours slide out of the way, and each
 * change of place ticks. TalkBack gets Move up and Move down actions on the row instead.
 */
final class ExpressiveRows {
    /** A grouped row's corners: large at the group's ends, small between rows, and the gap. */
    static final float OUTER = 24;
    static final float INNER = 6;
    static final float GAP = 2;
    private static final String HANDLE = "drag_handle";

    private final Context ctx;
    private final boolean dark;
    private final ExpressiveSettings.Model model;
    private final Runnable onRedraw;

    /** {@code ctx} carries the Expressive theme; {@code redraw} draws the page again (posted). */
    ExpressiveRows(Context ctx, boolean dark, ExpressiveSettings.Model model, Runnable redraw) {
        this.ctx = ctx;
        this.dark = dark;
        this.model = model;
        this.onRedraw = redraw;
    }

    private void redraw() {
        this.onRedraw.run();
    }

    /**
     * Adds {@code section} to {@code list}: its label (or a gap after an earlier section), its cards
     * and rows as one segmented group, and its footer.
     */
    void section(LinearLayout list, GlassModel.Section section, ExpressiveCards look) {
        if (section.header != null) {
            TextView label = text(section.header, R.attr.textAppearanceLabelLargeEmphasized, R.attr.colorPrimary);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(dp(16), dp(20), dp(16), dp(8));
            list.addView(label, lp);
        } else if (list.getChildCount() > 0) {
            list.addView(new View(this.ctx), new LinearLayout.LayoutParams(-1, dp(14)));
        }
        int cards = section.cards.size();
        int n = section.rows.size();
        for (int i = 0; i < cards; i++) {
            GlassModel.Card c = section.cards.get(i);
            if (c.kind == GlassModel.Card.Kind.HEADING) {
                list.addView(look.heading(c.title));
                continue;
            }
            // A section's cards and rows are one group: a card joins its neighbours in the section.
            boolean prev = i > 0 && section.cards.get(i - 1).kind != GlassModel.Card.Kind.HEADING;
            boolean next = i + 1 < cards
                    ? section.cards.get(i + 1).kind != GlassModel.Card.Kind.HEADING
                    : n > 0;
            list.addView(look.card(c, prev, next));
        }
        boolean afterCard = cards > 0 && section.cards.get(cards - 1).kind != GlassModel.Card.Kind.HEADING;
        boolean movable = section.onMove != null && n > 1;
        List<View> rows = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            View row = row(section.rows.get(i), i == 0 && !afterCard, i == n - 1);
            if (movable) {
                addHandle(row);
            }
            rows.add(row);
            list.addView(row);
        }
        if (movable) {
            draggable(rows, section.onMove);
        }
        if (section.footer != null) {
            TextView foot = text(section.footer, R.attr.textAppearanceBodySmall, R.attr.colorOnSurfaceVariant);
            LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1, -2);
            fp.setMargins(dp(16), dp(8), dp(16), 0);
            list.addView(foot, fp);
        }
    }

    /** A drag handle at the row's end (after its switch), as in Android's own reorderable lists. */
    private void addHandle(View row) {
        if (!(row instanceof MaterialCardView)) {
            return;
        }
        LinearLayout content = (LinearLayout) ((MaterialCardView) row).getChildAt(0);
        LinearLayout line = (LinearLayout) content.getChildAt(0);
        ImageView handle = new ImageView(this.ctx);
        handle.setTag(HANDLE);
        handle.setImageResource(R.drawable.ic_m3_drag_handle);
        handle.setImageTintList(ColorStateList.valueOf(color(R.attr.colorOnSurfaceVariant)));
        handle.setScaleType(ImageView.ScaleType.CENTER);
        // TalkBack reads the row's own Move up and Move down actions; the handle is touch only (its
        // label is for UI tests, which find it in the view tree).
        handle.setContentDescription("Reorder");
        handle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(dp(48), dp(48));
        hp.leftMargin = dp(4);
        hp.rightMargin = -dp(8);
        line.addView(handle, hp);
        // the row's own padding would make it taller than its neighbours with a 48 dp handle
        content.setPadding(content.getPaddingLeft(), dp(4), content.getPaddingRight(), dp(4));
        line.setMinimumHeight(dp(64));
    }

    private void draggable(List<View> rows, GlassModel.Mover mover) {
        int count = rows.size();
        for (int i = 0; i < count; i++) {
            View row = rows.get(i);
            int index = i;
            View handle = row.findViewWithTag(HANDLE);
            if (handle != null) {
                handle.setOnTouchListener(new Drag(rows, index, mover));
            }
            if (index > 0) {
                ViewCompat.addAccessibilityAction(row, "Move up", (v, args) -> {
                    mover.move(index, index - 1);
                    redraw();
                    return true;
                });
            }
            if (index < count - 1) {
                ViewCompat.addAccessibilityAction(row, "Move down", (v, args) -> {
                    mover.move(index, index + 1);
                    redraw();
                    return true;
                });
            }
        }
    }

    /**
     * Drags one row by its handle: the row lifts (raised, fully rounded, a stronger surface) and
     * follows the finger inside the list, the rows it passes slide over by its height, and letting
     * go settles it into its new place and saves the order.
     */
    private final class Drag implements View.OnTouchListener {
        private final List<View> rows;
        private final int from;
        private final GlassModel.Mover mover;
        private float startY;
        private int target;
        private boolean active;
        private ShapeAppearanceModel shape;

        Drag(List<View> rows, int from, GlassModel.Mover mover) {
            this.rows = rows;
            this.from = from;
            this.mover = mover;
        }

        @Override
        public boolean onTouch(View handle, MotionEvent e) {
            View row = this.rows.get(this.from);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    handle.getParent().requestDisallowInterceptTouchEvent(true);
                    this.startY = e.getRawY();
                    this.target = this.from;
                    this.active = true;
                    lift(row, true);
                    AppHaptics.confirm(row);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    if (!this.active) {
                        return false;
                    }
                    View first = this.rows.get(0);
                    View last = this.rows.get(this.rows.size() - 1);
                    float dy = e.getRawY() - this.startY;
                    dy = Math.max(first.getTop() - row.getTop(), Math.min(last.getBottom() - row.getBottom(), dy));
                    row.setTranslationY(dy);
                    float center = row.getTop() + row.getHeight() / 2f + dy;
                    int to = this.from;
                    for (int j = this.from + 1; j < this.rows.size(); j++) {
                        if (center > mid(this.rows.get(j))) {
                            to = j;
                        }
                    }
                    for (int j = this.from - 1; j >= 0; j--) {
                        if (center < mid(this.rows.get(j))) {
                            to = j;
                        }
                    }
                    if (to != this.target) {
                        this.target = to;
                        AppHaptics.tick(row);
                        shiftOthers(row);
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    if (!this.active) {
                        return false;
                    }
                    this.active = false;
                    handle.getParent().requestDisallowInterceptTouchEvent(false);
                    int to = this.target;
                    if (to == this.from && e.getActionMasked() == MotionEvent.ACTION_UP) {
                        // a tap on the handle, which does nothing more
                        handle.performClick();
                    }
                    float end = to > this.from ? this.rows.get(to).getBottom() - row.getBottom()
                            : to < this.from ? this.rows.get(to).getTop() - row.getTop() : 0f;
                    row.animate().translationY(end).setDuration(motion(160))
                            .setInterpolator(new android.view.animation.DecelerateInterpolator())
                            .withEndAction(() -> {
                                if (to != this.from) {
                                    android.util.Log.i("CodexGlass", "reorder rows " + this.from + " " + to);
                                    AppHaptics.tick(row);
                                    this.mover.move(this.from, to);
                                    redraw();
                                } else {
                                    lift(row, false);
                                }
                            }).start();
                    return true;
                }
                default:
                    return true;
            }
        }

        private float mid(View v) {
            return v.getTop() + v.getHeight() / 2f;
        }

        private void shiftOthers(View row) {
            float shift = row.getHeight() + dp(GAP);
            for (int j = 0; j < this.rows.size(); j++) {
                if (j == this.from) {
                    continue;
                }
                float to = this.from < j && j <= this.target ? -shift
                        : this.target <= j && j < this.from ? shift : 0f;
                this.rows.get(j).animate().translationY(to).setDuration(motion(180))
                        .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
            }
        }

        private void lift(View row, boolean on) {
            if (!(row instanceof MaterialCardView)) {
                return;
            }
            MaterialCardView card = (MaterialCardView) row;
            if (on) {
                this.shape = card.getShapeAppearanceModel();
                card.setShapeAppearanceModel(ShapeAppearanceModel.builder().setAllCornerSizes(dp(OUTER)).build());
                card.setCardBackgroundColor(color(R.attr.colorSurfaceContainerHighest));
                card.setCardElevation(dp(6));
                card.setTranslationZ(dp(2));
            } else {
                if (this.shape != null) {
                    card.setShapeAppearanceModel(this.shape);
                }
                card.setCardBackgroundColor(rowColor());
                card.setCardElevation(0);
                card.setTranslationZ(0);
                card.setTranslationY(0);
            }
        }
    }

    private long motion(long ms) {
        return ReducedMotion.isRequested(this.ctx) ? 0 : ms;
    }

    private int rowColor() {
        return color(this.dark ? R.attr.colorSurfaceContainerHigh : R.attr.colorSurfaceContainerLowest);
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

    /** One row in a segmented card: large corners at the group's ends, small ones inside. */
    private View row(Row r, boolean first, boolean last) {
        MaterialCardView card = new MaterialCardView(this.ctx);
        card.setCardElevation(0);
        card.setStrokeWidth(0);
        card.setCardBackgroundColor(rowColor());
        float big = dp(OUTER);
        float small = dp(INNER);
        card.setShapeAppearanceModel(ShapeAppearanceModel.builder()
                .setTopLeftCornerSize(first ? big : small).setTopRightCornerSize(first ? big : small)
                .setBottomLeftCornerSize(last ? big : small).setBottomRightCornerSize(last ? big : small)
                .build());
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
        cp.bottomMargin = last ? 0 : dp(GAP);
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
            summary = ExpressiveSettings.choiceSummary(r.entries[r.selected], summary);
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
                // Material's 52 dp switch, not One UI's narrower fixed width (ExpressiveSwitch)
                MaterialSwitch sw = new ExpressiveSwitch(this.ctx);
                sw.setChecked(r.checked);
                sw.setEnabled(r.enabled);
                sw.setOnCheckedChangeListener((b, on) -> {
                    AppHaptics.tick(b);
                    toggle(r, on);
                    redraw();
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
                    redraw();
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
                    redraw();
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
                } else if (r.secondary) {
                    b.setBackgroundTintList(ColorStateList.valueOf(color(R.attr.colorSecondaryContainer)));
                    b.setTextColor(color(R.attr.colorOnSecondaryContainer));
                }
                b.setEnabled(r.enabled);
                b.setOnClickListener(v -> {
                    press(r);
                    redraw();
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
                redraw();
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
                    redraw();
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
                redraw();
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