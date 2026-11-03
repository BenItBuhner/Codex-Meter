package dev.bennett.codexmeter;

import android.content.Context;
import android.content.DialogInterface;
import android.widget.Button;
import android.widget.ListAdapter;
import android.widget.ListView;
import androidx.appcompat.app.AlertDialog;
import dev.bennett.codexmeter.glass.GlassModel;
import java.util.Locale;

/**
 * An {@link AppDialog} shown by the Liquid Glass screens: Glassé's alert, or its sheet for lists.
 * It stands in for the AlertDialog the app's code expects (buttons, list, dismiss and the
 * listeners), so the settings code that builds dialogs runs unchanged in both designs.
 */
final class GlassDialog extends AlertDialog {
    private final GlassApp app;
    private final AppDialog spec;
    private final Button[] buttons = new Button[3];
    private final GlassModel.Dialog model = new GlassModel.Dialog();
    private OnShowListener showListener;
    private OnDismissListener dismissListener;
    private ChoiceList listView;
    private boolean showing;

    GlassDialog(Context context, GlassApp app, AppDialog spec) {
        super(context);
        this.app = app;
        this.spec = spec;
        this.dismissListener = spec.dismissListener;
        model.title = spec.title == null ? "" : spec.title.toString();
        model.message = spec.message == null ? null : spec.message.toString();
        CharSequence[] labels = spec.items;
        if (labels == null && spec.adapter != null) {
            labels = labels(spec.adapter);
        }
        if (labels != null) {
            model.items = new String[labels.length];
            for (int i = 0; i < labels.length; i++) {
                model.items[i] = String.valueOf(labels[i]);
            }
            if (spec.multiChoice) {
                model.checked = spec.checkedItems.clone();
            } else {
                model.selected = spec.singleChoice ? spec.checkedItem : -1;
            }
            model.onItem = this::pick;
        }
        // iOS order: cancel first, the action last.
        if (spec.negativeText != null) {
            model.buttons.add(new GlassModel.Dialog.Button(spec.negativeText.toString(),
                    GlassModel.Dialog.CANCEL, () -> press(BUTTON_NEGATIVE)));
        }
        if (spec.neutralText != null) {
            model.buttons.add(new GlassModel.Dialog.Button(spec.neutralText.toString(),
                    GlassModel.Dialog.DEFAULT, () -> press(BUTTON_NEUTRAL)));
        }
        if (spec.positiveText != null) {
            String label = spec.positiveText.toString();
            model.buttons.add(new GlassModel.Dialog.Button(label,
                    destructive(label) ? GlassModel.Dialog.DESTRUCTIVE : GlassModel.Dialog.PROMINENT,
                    () -> press(BUTTON_POSITIVE)));
        }
        model.onClose = this::closedByUser;
    }

    GlassModel.Dialog model() {
        return model;
    }

    private static CharSequence[] labels(ListAdapter adapter) {
        CharSequence[] out = new CharSequence[adapter.getCount()];
        for (int i = 0; i < out.length; i++) {
            Object item = adapter.getItem(i);
            out[i] = item == null ? "" : item.toString();
        }
        return out;
    }

    private static boolean destructive(String label) {
        String l = label.toLowerCase(Locale.ROOT);
        return l.startsWith("clear") || l.startsWith("delete") || l.startsWith("remove")
                || l.startsWith("sign out") || l.startsWith("reset") || l.startsWith("erase");
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

    /** For code that takes over a button (to check input first): a click listener set on it replaces closing. */
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
        if (showing) {
            return;
        }
        showing = true;
        app.showDialog(this);
        if (showListener != null) {
            showListener.onShow(this);
        }
    }

    @Override
    public void dismiss() {
        if (!showing) {
            return;
        }
        showing = false;
        app.closeDialog(this);
        if (dismissListener != null) {
            dismissListener.onDismiss(this);
        }
    }

    @Override
    public void cancel() {
        if (showing && spec.cancelListener != null) {
            spec.cancelListener.onCancel(this);
        }
        dismiss();
    }

    /** Swiped or tapped away: Android's cancel. */
    private void closedByUser() {
        if (showing) {
            cancel();
        }
    }

    private void press(int which) {
        Button taken = getButton(which);
        if (taken.hasOnClickListeners()) {
            taken.performClick();
            return;
        }
        DialogInterface.OnClickListener listener = which == BUTTON_POSITIVE ? spec.positiveListener
                : which == BUTTON_NEGATIVE ? spec.negativeListener : spec.neutralListener;
        if (listener != null) {
            listener.onClick(this, which);
        }
        dismiss();
    }

    private void pick(int index, boolean checked) {
        if (spec.multiChoice) {
            model.checked[index] = checked;
            if (spec.multiChoiceListener != null) {
                spec.multiChoiceListener.onClick(this, index, checked);
            }
            app.changed();
            return;
        }
        if (spec.singleChoice) {
            model.selected = index;
            if (spec.itemsListener != null) {
                spec.itemsListener.onClick(this, index);
            }
            app.changed();
            return;
        }
        // A plain list closes once an item is picked, as on Android.
        if (spec.itemsListener != null) {
            spec.itemsListener.onClick(this, index);
        }
        dismiss();
    }

    /** The list as the dialog's code sees it: checking an item updates the sheet. */
    private static final class ChoiceList extends ListView {
        private final GlassDialog dialog;

        ChoiceList(Context context, GlassDialog dialog) {
            super(context);
            this.dialog = dialog;
        }

        @Override
        public void setItemChecked(int position, boolean value) {
            GlassModel.Dialog m = dialog.model;
            if (m.checked != null && position >= 0 && position < m.checked.length) {
                m.checked[position] = value;
            } else if (m.items != null && value) {
                m.selected = position;
            }
            dialog.app.changed();
        }

        @Override
        public boolean isItemChecked(int position) {
            GlassModel.Dialog m = dialog.model;
            if (m.checked != null) {
                return position >= 0 && position < m.checked.length && m.checked[position];
            }
            return position == m.selected;
        }

        @Override
        public int getCheckedItemPosition() {
            return dialog.model.selected;
        }
    }
}
