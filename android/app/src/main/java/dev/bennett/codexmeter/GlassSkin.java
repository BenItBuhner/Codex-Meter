package dev.bennett.codexmeter;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.SpinnerAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.appcompat.widget.SeslSeekBar;
import androidx.appcompat.widget.SwitchCompat;
import com.benitbuhner.liquidglass.widget.GlassButton;
import com.benitbuhner.liquidglass.widget.GlassMenu;
import com.benitbuhner.liquidglass.widget.GlassSlider;
import com.benitbuhner.liquidglass.widget.GlassSwitch;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Turns a page's One UI controls into Liquid Glass ones. Every screen builds its content with SESL
 * views (switches, radio buttons, seek bars, spinners, pill buttons); rather than fork each screen,
 * this walks the page body as it is laid out and puts the matching Glasse control in place of each
 * one: {@link GlassSwitch} for switches and check boxes, a check circle for radio buttons,
 * {@link GlassSlider} for seek bars, a pull-down {@link GlassMenu} for spinners and
 * {@link GlassButton} for buttons.
 *
 * <p>The One UI control stays in the hierarchy, hidden, as the model: the screen's own code keeps
 * reading and writing it, a glass change is passed on through its public setters (so its listeners
 * fire as before) and each frame copies its state back to the glass control. Lists that recycle
 * rows (Settings) keep working, because each row's glass control is paired with that row's own
 * hidden control.
 */
final class GlassSkin {
    private final GlassPage page;
    private final List<Pair> pairs = new ArrayList<>();

    private interface Pair {
        /** Copies the hidden control's state to the glass one; false once the row is gone. */
        boolean sync();
    }

    private GlassSkin(GlassPage page) {
        this.page = page;
    }

    static void install(GlassPage page) {
        GlassSkin skin = new GlassSkin(page);
        page.root.setTag(R.id.glass_skin, skin);
        ViewTreeObserver observer = page.root.getViewTreeObserver();
        observer.addOnGlobalLayoutListener(() -> skin.skin(page.body));
        observer.addOnPreDrawListener(() -> {
            skin.syncAll();
            return true;
        });
    }

    /**
     * Skins {@code content} now, before it is shown: a dialog's custom view, which lives in an
     * overlay rather than the page body.
     */
    static void skinTree(View host, View content) {
        Object skin = host.getTag(R.id.glass_skin);
        if (skin instanceof GlassSkin) {
            ((GlassSkin) skin).skin(content);
        }
    }

    private void syncAll() {
        Iterator<Pair> it = pairs.iterator();
        while (it.hasNext()) {
            if (!it.next().sync()) {
                it.remove();
            }
        }
    }

    private void skin(View view) {
        if (view.getTag(R.id.glass_skinned) != null) {
            return;
        }
        if (view instanceof SwitchCompat) {
            skinSwitch((CompoundButton) view);
        } else if (view instanceof CheckBox) {
            skinCheckBox((CheckBox) view);
        } else if (view instanceof RadioButton) {
            skinRadio((RadioButton) view);
        } else if (view instanceof SeslSeekBar) {
            skinSeekBar((SeslSeekBar) view);
        } else if (view instanceof Spinner) {
            skinSpinner((Spinner) view);
        } else if (view instanceof Button && !(view instanceof CompoundButton)) {
            skinButton((Button) view);
        } else if (view instanceof EditText) {
            skinField((EditText) view);
        } else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            View[] children = new View[group.getChildCount()];
            for (int i = 0; i < children.length; i++) {
                children[i] = group.getChildAt(i);
            }
            for (View child : children) {
                skin(child);
            }
        }
    }

    /**
     * Puts {@code glass} where {@code model} was. The model stays visible to the screen's code but
     * takes no room: its own visibility changes (a button that appears once signed in) carry over
     * to the glass control each frame.
     */
    private static void replace(View model, View glass, int width, int height) {
        model.setTag(R.id.glass_skinned, Boolean.TRUE);
        glass.setTag(R.id.glass_skinned, Boolean.TRUE);
        ViewGroup parent = (ViewGroup) model.getParent();
        if (parent == null) {
            return;
        }
        ViewGroup.LayoutParams source = model.getLayoutParams();
        ViewGroup.LayoutParams params;
        if (source instanceof LinearLayout.LayoutParams) {
            params = new LinearLayout.LayoutParams((LinearLayout.LayoutParams) source);
        } else if (source instanceof FrameLayout.LayoutParams) {
            params = new FrameLayout.LayoutParams((FrameLayout.LayoutParams) source);
        } else if (source instanceof ViewGroup.MarginLayoutParams) {
            params = new ViewGroup.MarginLayoutParams((ViewGroup.MarginLayoutParams) source);
        } else {
            params = new ViewGroup.LayoutParams(source);
        }
        params.width = width;
        params.height = height;
        glass.setVisibility(model.getVisibility());
        parent.addView(glass, parent.indexOfChild(model) + 1, params);
        source.width = 0;
        source.height = 0;
        if (source instanceof ViewGroup.MarginLayoutParams) {
            ((ViewGroup.MarginLayoutParams) source).setMargins(0, 0, 0, 0);
        }
        if (source instanceof LinearLayout.LayoutParams) {
            ((LinearLayout.LayoutParams) source).weight = 0.0f;
        }
        model.setMinimumWidth(0);
        model.setMinimumHeight(0);
        model.setPadding(0, 0, 0, 0);
        model.setLayoutParams(source);
        model.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    }

    private static boolean gone(View model, View glass) {
        return !model.isAttachedToWindow() && !glass.isAttachedToWindow();
    }

    /** The model's visibility and enabled state, mirrored on the glass control. */
    private static void mirror(View model, View glass) {
        boolean enabled = model.isEnabled();
        if (glass.isEnabled() != enabled) {
            glass.setEnabled(enabled);
            glass.setAlpha(enabled ? 1.0f : 0.4f);
        }
        if (glass.getVisibility() != model.getVisibility()) {
            glass.setVisibility(model.getVisibility());
        }
    }

    // ---------------------------------------------------------------- switches

    private void skinSwitch(CompoundButton model) {
        GlassSwitch glass = new GlassSwitch(model.getContext());
        glass.setOnColor(0xFF34C759);
        glass.setChecked(model.isChecked());
        glass.setContentDescription(model.getContentDescription());
        glass.setOnCheckedChangeListener((view, checked) -> {
            if (model.isChecked() != checked) {
                model.setChecked(checked);
            }
        });
        // A switch inside a row: taps on the row still go to the row (SESL list rows toggle the
        // preference); only the switch itself is glass.
        replace(model, glass, ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        pairs.add(() -> {
            if (gone(model, glass)) {
                return false;
            }
            if (glass.isChecked() != model.isChecked()) {
                glass.setChecked(model.isChecked());
            }
            mirror(model, glass);
            return true;
        });
    }

    /** iOS has no check boxes: a check box with a label becomes the label and a switch. */
    private void skinCheckBox(CheckBox model) {
        Context context = model.getContext();
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = new TextView(context);
        label.setText(model.getText());
        label.setTextSize(17.0f);
        label.setTextColor(model.getCurrentTextColor());
        row.addView(label, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        GlassSwitch glass = new GlassSwitch(context);
        glass.setOnColor(0xFF34C759);
        glass.setChecked(model.isChecked());
        glass.setOnCheckedChangeListener((view, checked) -> {
            if (model.isChecked() != checked) {
                model.setChecked(checked);
            }
        });
        row.addView(glass);
        replace(model, row, ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        pairs.add(() -> {
            if (gone(model, row)) {
                return false;
            }
            if (glass.isChecked() != model.isChecked()) {
                glass.setChecked(model.isChecked());
            }
            if (!label.getText().toString().contentEquals(model.getText())) {
                label.setText(model.getText());
            }
            mirror(model, row);
            return true;
        });
    }

    // ---------------------------------------------------------------- radio buttons

    private void skinRadio(RadioButton model) {
        CheckCircle glass = new CheckCircle(model.getContext(), Ui.accent(model.getContext(),
                Ui.isDark(model.getContext())), Ui.isDark(model.getContext()));
        glass.setChecked(model.isChecked());
        glass.setOnClickListener(view -> model.performClick());
        int size = Ui.dp(model.getContext(), 24.0f);
        replace(model, glass, size, size);
        if (model.getLayoutParams() instanceof ViewGroup.MarginLayoutParams
                && glass.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams params =
                    (ViewGroup.MarginLayoutParams) glass.getLayoutParams();
            params.setMarginEnd(Math.max(params.getMarginEnd(), Ui.dp(model.getContext(), 8.0f)));
        }
        pairs.add(() -> {
            if (gone(model, glass)) {
                return false;
            }
            glass.setChecked(model.isChecked());
            mirror(model, glass);
            return true;
        });
    }

    /**
     * iOS's selection circle (the Appearance page's Light / Dark picker): an empty ring, or an
     * accent disc with a white check.
     */
    static final class CheckCircle extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path check = new Path();
        private final int accent;
        private final boolean dark;
        private boolean checked;

        CheckCircle(Context context, int accent, boolean dark) {
            super(context);
            this.accent = accent;
            this.dark = dark;
            setClickable(true);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void setChecked(boolean value) {
            if (checked != value) {
                checked = value;
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float d = getResources().getDisplayMetrics().density;
            float cx = getWidth() / 2.0f;
            float cy = getHeight() / 2.0f;
            float r = Math.min(cx, cy) - d;
            if (checked) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(accent);
                canvas.drawCircle(cx, cy, r, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(2.0f * d);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeJoin(Paint.Join.ROUND);
                paint.setColor(0xFFFFFFFF);
                check.reset();
                check.moveTo(cx - r * 0.42f, cy + r * 0.02f);
                check.lineTo(cx - r * 0.1f, cy + r * 0.34f);
                check.lineTo(cx + r * 0.45f, cy - r * 0.3f);
                canvas.drawPath(check, paint);
            } else {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(1.5f * d);
                paint.setColor(dark ? 0x66EBEBF5 : 0x4D3C3C43);
                canvas.drawCircle(cx, cy, r - 0.75f * d, paint);
            }
        }
    }

    // ---------------------------------------------------------------- seek bars

    /**
     * A seek bar becomes a {@link GlassSlider} with a tick per step. A seek bar drawn over custom
     * track art (widget opacity) brings that art down with it: every sibling in its frame hides.
     */
    private void skinSeekBar(SeslSeekBar model) {
        Context context = model.getContext();
        GlassSlider glass = new GlassSlider(context);
        glass.setMin(model.getMin());
        glass.setMax(model.getMax());
        glass.setStep(1.0f);
        glass.setShowTicks(model.getMax() - model.getMin() <= 10);
        glass.setValue(model.getProgress());
        glass.setOnChangeListener((slider, value, fromUser) -> {
            int progress = Math.round(value);
            if (fromUser && model.getProgress() != progress) {
                model.setProgress(progress);
            }
        });
        ViewGroup parent = (ViewGroup) model.getParent();
        if (parent instanceof FrameLayout) {
            for (int i = 0; i < parent.getChildCount(); i++) {
                View sibling = parent.getChildAt(i);
                if (sibling != model) {
                    sibling.setVisibility(View.GONE);
                }
            }
        }
        replace(model, glass, ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(context, 44.0f));
        pairs.add(() -> {
            if (gone(model, glass)) {
                return false;
            }
            if (Math.round(glass.getValue()) != model.getProgress() && !glass.isPressed()) {
                glass.setValue(model.getProgress());
            }
            mirror(model, glass);
            return true;
        });
    }

    // ---------------------------------------------------------------- spinners

    /** A visible spinner becomes iOS's pull-down button: the value and ⌃⌄, opening a glass menu. */
    private void skinSpinner(Spinner model) {
        if (model.getVisibility() != View.VISIBLE) {
            return; // a hidden spinner holds a choice another control shows; check again later
        }
        Context context = model.getContext();
        boolean dark = Ui.isDark(context);
        TextView glass = new TextView(context);
        glass.setTextSize(17.0f);
        glass.setTextColor(Ui.accent(context, dark));
        glass.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        glass.setSingleLine(true);
        glass.setPadding(Ui.dp(context, 8.0f), 0, Ui.dp(context, 4.0f), 0);
        Drawable updown = context.getDrawable(R.drawable.glass_ic_chevron_updown);
        if (updown != null) {
            updown = updown.mutate();
            updown.setTintList(ColorStateList.valueOf(Ui.accent(context, dark)));
        }
        glass.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, updown, null);
        glass.setCompoundDrawablePadding(Ui.dp(context, 6.0f));
        glass.setOnClickListener(view -> showSpinnerMenu(model, glass));
        replace(model, glass, ViewGroup.LayoutParams.WRAP_CONTENT,
                model.getLayoutParams().height > 0 ? model.getLayoutParams().height
                        : Ui.dp(context, 44.0f));
        pairs.add(() -> {
            if (gone(model, glass)) {
                return false;
            }
            Object item = model.getSelectedItem();
            String text = item == null ? "" : item.toString();
            if (!text.contentEquals(glass.getText())) {
                glass.setText(text);
            }
            mirror(model, glass);
            return true;
        });
    }

    private static void showSpinnerMenu(Spinner model, View anchor) {
        SpinnerAdapter adapter = model.getAdapter();
        if (adapter == null) {
            return;
        }
        Context context = model.getContext();
        List<GlassMenu.Item> rows = new ArrayList<>();
        for (int i = 0; i < adapter.getCount(); i++) {
            int position = i;
            Object item = adapter.getItem(i);
            rows.add(new GlassMenu.Item(item == null ? "" : item.toString(),
                    i == model.getSelectedItemPosition() ? GlassDialogs.check(context) : null,
                    false, false, () -> model.setSelection(position)));
        }
        GlassMenu.show(anchor, rows);
    }

    // ---------------------------------------------------------------- text fields

    /** A text field becomes iOS's rounded grey field (no One UI underline). */
    private static void skinField(EditText field) {
        Context context = field.getContext();
        boolean dark = Ui.isDark(context);
        field.setTag(R.id.glass_skinned, Boolean.TRUE);
        android.graphics.drawable.GradientDrawable fill =
                new android.graphics.drawable.GradientDrawable();
        fill.setCornerRadius(Ui.dp(context, 12.0f));
        fill.setColor(dark ? 0x3D767680 : 0x1F767680);
        field.setBackground(fill);
        field.setTextSize(17.0f);
        int side = Ui.dp(context, 14.0f);
        field.setPadding(side, field.getPaddingTop(), side, field.getPaddingBottom());
    }

    // ---------------------------------------------------------------- buttons

    private void skinButton(Button model) {
        Context context = model.getContext();
        GlassButton glass = new GlassButton(context);
        glass.setText(model.getText());
        boolean prominent = Boolean.TRUE.equals(model.getTag(R.id.glass_prominent));
        glass.setProminent(prominent);
        Drawable[] icons = model.getCompoundDrawablesRelative();
        if (icons[0] != null && !prominent) {
            glass.setIcon(icons[0].getConstantState() == null ? icons[0]
                    : icons[0].getConstantState().newDrawable());
        }
        glass.setSizeDp(prominent ? 50.0f : 46.0f);
        glass.setOnClickListener(view -> model.performClick());
        ViewGroup.LayoutParams source = model.getLayoutParams();
        int width = source != null && source.width == ViewGroup.LayoutParams.MATCH_PARENT
                ? ViewGroup.LayoutParams.MATCH_PARENT
                : source != null && source.width == 0 ? 0 : ViewGroup.LayoutParams.WRAP_CONTENT;
        replace(model, glass, width, ViewGroup.LayoutParams.WRAP_CONTENT);
        pairs.add(() -> {
            if (gone(model, glass)) {
                return false;
            }
            CharSequence text = model.getText();
            if (text != null && !text.toString().contentEquals(
                    glass.getText() == null ? "" : glass.getText())) {
                glass.setText(text);
            }
            mirror(model, glass);
            return true;
        });
    }
}
