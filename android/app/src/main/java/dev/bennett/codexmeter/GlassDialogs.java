package dev.bennett.codexmeter;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.DialogInterface;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import com.benitbuhner.liquidglass.LiquidGlassLayout;
import com.benitbuhner.liquidglass.widget.GlassAlert;
import com.benitbuhner.liquidglass.widget.GlassButton;
import com.benitbuhner.liquidglass.widget.GlassListRow;
import com.benitbuhner.liquidglass.widget.GlassListSection;
import com.benitbuhner.liquidglass.widget.GlassMenu;
import com.benitbuhner.liquidglass.widget.GlassSheet;
import com.benitbuhner.liquidglass.widget.GlassSwitch;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Dialogs in the Liquid Glass design. A plain question (title, message, buttons) is a
 * {@link GlassAlert}; a list (items, single or multiple choice) or a custom view is a
 * {@link GlassSheet} holding an iOS grouped list, with glass buttons at its foot.
 */
final class GlassDialogs {
    private GlassDialogs() {
    }

    /** The Liquid Glass page of {@code context}'s activity, or null when the design is off. */
    static LiquidGlassLayout host(Context context) {
        Activity activity = activity(context);
        if (activity == null || !AppPreferences.isLiquidGlassDesign(activity)) {
            return null;
        }
        View root = activity.findViewById(R.id.glass_page_root);
        return root instanceof LiquidGlassLayout ? (LiquidGlassLayout) root : null;
    }

    static Activity activity(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) {
                return (Activity) context;
            }
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    /** The check mark of a picked menu row or list row, in the accent color. */
    static Drawable check(Context context) {
        Drawable icon = context.getDrawable(com.benitbuhner.liquidglass.R.drawable.glasse_ic_check);
        if (icon != null) {
            icon = icon.mutate();
            icon.setTint(Ui.accent(context, Ui.isDark(context)));
        }
        return icon;
    }

    /**
     * A single choice as iOS's pull-down menu out of {@code anchor} (the row that was tapped),
     * with a check mark on the current choice.
     */
    static void choose(View anchor, CharSequence[] labels, int selected,
            OneUiChoiceDialog.OnChoiceSelected listener) {
        List<GlassMenu.Item> rows = new ArrayList<>();
        for (int i = 0; i < labels.length; i++) {
            int position = i;
            rows.add(new GlassMenu.Item(labels[i], i == selected ? check(anchor.getContext())
                    : null, false, false, () -> listener.onSelected(position)));
        }
        new GlassMenu(anchor).setItems(rows).setWidthDp(Math.min(300.0f, Math.max(220.0f,
                longest(labels) * 9.0f + 80.0f))).show();
    }

    private static int longest(CharSequence[] labels) {
        int max = 0;
        for (CharSequence label : labels) {
            max = Math.max(max, label == null ? 0 : label.length());
        }
        return max;
    }

    /** Words that make the confirming button of an alert destructive (red), as on iOS. */
    private static boolean destructive(CharSequence label) {
        if (label == null) {
            return false;
        }
        String value = label.toString().toLowerCase(Locale.ROOT);
        return value.startsWith("sign out") || value.startsWith("delete")
                || value.startsWith("remove") || value.startsWith("clear")
                || value.startsWith("erase") || value.startsWith("uninstall");
    }

    /** An AlertDialog that shows as glass: what {@link AppDialog#create()} returns in this design. */
    static final class Proxy extends AlertDialog {
        private final AppDialog spec;
        private final LiquidGlassLayout host;
        private final Button[] buttons = new Button[3];
        private OnShowListener showListener;
        private OnDismissListener dismissListener;
        private GlassAlert alert;
        private GlassSheet sheet;
        private boolean showing;
        private ChoiceList listView;
        private final List<GlassSwitch> switches = new ArrayList<>();
        private final List<GlassListRow> choiceRows = new ArrayList<>();

        Proxy(Context context, AppDialog spec) {
            super(context);
            this.spec = spec;
            this.host = host(context);
            this.dismissListener = spec.dismissListener;
        }

        @Override
        public void setOnShowListener(OnShowListener listener) {
            showListener = listener;
        }

        @Override
        public void setOnDismissListener(OnDismissListener listener) {
            dismissListener = listener;
        }

        @Override
        public boolean isShowing() {
            return showing;
        }

        /**
         * The dialog's buttons, for code that takes over a button's click (to validate before
         * closing): a click listener set on one replaces closing the dialog.
         */
        @Override
        public Button getButton(int which) {
            int index = which == BUTTON_POSITIVE ? 0 : which == BUTTON_NEGATIVE ? 1 : 2;
            if (buttons[index] == null) {
                buttons[index] = new Button(getContext());
            }
            return buttons[index];
        }

        @Override
        public ListView getListView() {
            if (listView == null) {
                listView = new ChoiceList(getContext(), this);
            }
            return listView;
        }

        @Override
        public void show() {
            if (showing || host == null) {
                return;
            }
            showing = true;
            if (spec.items == null && spec.adapter == null && spec.view == null) {
                showAlert();
            } else {
                showSheet();
            }
            if (showListener != null) {
                showListener.onShow(this);
            }
        }

        @Override
        public void dismiss() {
            if (alert != null) {
                alert.dismiss();
            } else if (sheet != null) {
                sheet.dismiss();
            } else {
                gone();
            }
        }

        @Override
        public void cancel() {
            if (spec.cancelListener != null && showing) {
                spec.cancelListener.onCancel(this);
            }
            dismiss();
        }

        private void gone() {
            if (!showing) {
                return;
            }
            showing = false;
            alert = null;
            sheet = null;
            if (dismissListener != null) {
                dismissListener.onDismiss(this);
            }
        }

        /** Runs button {@code which}: its taken-over click, or its listener and then close. */
        private void press(int which) {
            Button taken = getButton(which);
            if (taken.hasOnClickListeners()) {
                taken.performClick();
                return;
            }
            DialogInterface.OnClickListener listener = which == BUTTON_POSITIVE
                    ? spec.positiveListener : which == BUTTON_NEGATIVE ? spec.negativeListener
                    : spec.neutralListener;
            if (listener != null) {
                listener.onClick(this, which);
            }
            dismiss();
        }

        // ---------------------------------------------------------------- alert

        private void showAlert() {
            GlassAlert.Builder builder = new GlassAlert.Builder(host)
                    .setTitle(spec.title == null ? "" : spec.title)
                    .setMessage(spec.message)
                    .setOnDismissListener(this::gone);
            boolean three = spec.neutralText != null && spec.positiveText != null
                    && spec.negativeText != null;
            if (three) {
                // Stacked, as iOS stacks three: the action, the other one, then Cancel.
                addPositive(builder);
                builder.addAction(spec.neutralText, GlassAlert.DEFAULT,
                        () -> run(BUTTON_NEUTRAL));
                builder.addAction(spec.negativeText, GlassAlert.CANCEL,
                        () -> run(BUTTON_NEGATIVE));
            } else {
                if (spec.negativeText != null) {
                    builder.addAction(spec.negativeText, GlassAlert.CANCEL,
                            () -> run(BUTTON_NEGATIVE));
                }
                if (spec.neutralText != null) {
                    builder.addAction(spec.neutralText, spec.positiveText == null
                            ? GlassAlert.PROMINENT : GlassAlert.DEFAULT,
                            () -> run(BUTTON_NEUTRAL));
                }
                addPositive(builder);
            }
            alert = builder.show();
        }

        private void addPositive(GlassAlert.Builder builder) {
            if (spec.positiveText != null) {
                builder.addAction(spec.positiveText, destructive(spec.positiveText)
                        ? GlassAlert.DESTRUCTIVE : GlassAlert.PROMINENT,
                        () -> run(BUTTON_POSITIVE));
            }
        }

        /** A glass alert closes itself after an action; only the listener runs here. */
        private void run(int which) {
            DialogInterface.OnClickListener listener = which == BUTTON_POSITIVE
                    ? spec.positiveListener : which == BUTTON_NEGATIVE ? spec.negativeListener
                    : spec.neutralListener;
            if (listener != null) {
                listener.onClick(this, which);
            }
        }

        // ---------------------------------------------------------------- sheet

        private void showSheet() {
            Context context = getContext();
            boolean dark = Ui.isDark(context);
            LinearLayout column = new LinearLayout(context);
            column.setOrientation(LinearLayout.VERTICAL);
            if (spec.message != null) {
                TextView message = Ui.text(context, spec.message.toString(), 15.0f,
                        Ui.secondaryText(dark));
                message.setPadding(Ui.dp(context, 4.0f), 0, Ui.dp(context, 4.0f),
                        Ui.dp(context, 16.0f));
                column.addView(message);
            }
            if (spec.view != null) {
                if (spec.view.getParent() instanceof ViewGroup) {
                    ((ViewGroup) spec.view.getParent()).removeView(spec.view);
                }
                GlassSkin.skinTree(host, spec.view);
                column.addView(spec.view, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            if (spec.items != null || spec.adapter != null) {
                column.addView(list(context));
            }
            ScrollView scroll = new ScrollView(context);
            scroll.setVerticalScrollBarEnabled(false);
            scroll.setClipToPadding(false);
            scroll.addView(column, new ScrollView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            LinearLayout content = new LinearLayout(context);
            content.setOrientation(LinearLayout.VERTICAL);
            content.addView(scroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f));
            View actions = actions(context);
            if (actions != null) {
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                params.topMargin = Ui.dp(context, 14.0f);
                content.addView(actions, params);
            }
            float fraction = sheetFraction(context, column, actions);
            sheet = new GlassSheet(host)
                    .setTitle(spec.title)
                    .setContent(content)
                    .setDetents(fraction)
                    .setOnDismissListener(this::gone);
            sheet.show();
        }

        /** The share of the screen the sheet needs: its rows and buttons, at most 92 %. */
        private static float sheetFraction(Context context, View column, View actions) {
            DisplayMetrics metrics = context.getResources().getDisplayMetrics();
            int width = metrics.widthPixels - Ui.dp(context, 40.0f);
            column.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int height = column.getMeasuredHeight() + Ui.dp(context, 52.0f + 24.0f + 24.0f);
            if (actions != null) {
                height += Ui.dp(context, 14.0f + 50.0f);
            }
            return Math.max(0.28f, Math.min(0.92f, height / (float) metrics.heightPixels));
        }

        private View list(Context context) {
            GlassListSection section = new GlassListSection(context);
            int count = spec.items != null ? spec.items.length : spec.adapter.getCount();
            for (int i = 0; i < count; i++) {
                int position = i;
                GlassListRow row = new GlassListRow(context);
                CharSequence label;
                Drawable icon = null;
                if (spec.items != null) {
                    label = spec.items[i];
                } else {
                    View item = spec.adapter.getView(i, null, section);
                    if (item instanceof TextView) {
                        label = ((TextView) item).getText();
                        icon = ((TextView) item).getCompoundDrawablesRelative()[0];
                    } else {
                        Object value = spec.adapter.getItem(i);
                        label = value == null ? "" : value.toString();
                    }
                }
                row.setLabel(label == null ? "" : label);
                if (icon != null) {
                    row.setGlyph(icon, 0);
                }
                if (spec.multiChoice) {
                    GlassSwitch toggle = new GlassSwitch(context);
                    toggle.setOnColor(0xFF34C759);
                    toggle.setChecked(spec.checkedItems[i]);
                    toggle.setOnCheckedChangeListener((view, checked) -> {
                        spec.checkedItems[position] = checked;
                        if (spec.multiChoiceListener != null) {
                            spec.multiChoiceListener.onClick(this, position, checked);
                        }
                    });
                    switches.add(toggle);
                    row.setTrailing(toggle);
                    row.setOnClickListener(view -> toggle.toggle());
                } else if (spec.singleChoice) {
                    choiceRows.add(row);
                    markChoice(row, i == spec.checkedItem);
                    row.setOnClickListener(view -> {
                        spec.checkedItem = position;
                        for (int r = 0; r < choiceRows.size(); r++) {
                            markChoice(choiceRows.get(r), r == position);
                        }
                        if (spec.itemsListener != null) {
                            spec.itemsListener.onClick(this, position);
                        }
                    });
                } else {
                    row.setOnClickListener(view -> {
                        if (spec.itemsListener != null) {
                            spec.itemsListener.onClick(this, position);
                        }
                        dismiss();
                    });
                }
                section.addRow(row);
            }
            return section;
        }

        private void markChoice(GlassListRow row, boolean checked) {
            if (checked) {
                android.widget.ImageView mark = new android.widget.ImageView(getContext());
                mark.setImageDrawable(check(getContext()));
                int size = Ui.dp(getContext(), 22.0f);
                mark.setLayoutParams(new LinearLayout.LayoutParams(size, size));
                row.setTrailing(mark);
            } else {
                row.setTrailing(null);
            }
        }

        /** Glass buttons at the sheet's foot: the others plain, the confirming one prominent. */
        private View actions(Context context) {
            if (spec.positiveText == null && spec.negativeText == null
                    && spec.neutralText == null) {
                return null;
            }
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setClipChildren(false);
            row.setClipToPadding(false);
            addButton(row, spec.neutralText, BUTTON_NEUTRAL, false);
            addButton(row, spec.negativeText, BUTTON_NEGATIVE, false);
            addButton(row, spec.positiveText, BUTTON_POSITIVE, true);
            return row;
        }

        private void addButton(LinearLayout row, CharSequence text, int which, boolean main) {
            if (text == null) {
                return;
            }
            Context context = row.getContext();
            GlassButton button = new GlassButton(context);
            button.setText(text);
            button.setSizeDp(50.0f);
            button.setProminent(main);
            if (main && destructive(text)) {
                button.setGlassTint(Ui.danger(Ui.isDark(context)));
            }
            button.setOnClickListener(view -> press(which));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
            if (row.getChildCount() > 0) {
                params.setMarginStart(Ui.dp(context, 10.0f));
            }
            row.addView(button, params);
        }

        void setItemChecked(int position, boolean checked) {
            if (spec.checkedItems != null && position < spec.checkedItems.length) {
                spec.checkedItems[position] = checked;
            }
            if (position < switches.size()) {
                switches.get(position).setChecked(checked);
            }
        }
    }

    /** The list a multiple-choice dialog's code reads and unchecks rows through. */
    private static final class ChoiceList extends ListView {
        private final Proxy dialog;

        ChoiceList(Context context, Proxy dialog) {
            super(context);
            this.dialog = dialog;
        }

        @Override
        public void setItemChecked(int position, boolean value) {
            dialog.setItemChecked(position, value);
        }

        @Override
        public boolean isItemChecked(int position) {
            return dialog.spec.checkedItems != null && position < dialog.spec.checkedItems.length
                    && dialog.spec.checkedItems[position];
        }
    }
}
