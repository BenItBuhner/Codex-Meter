package dev.bennett.codexmeter;

import android.content.Context;
import android.content.DialogInterface;
import android.view.View;
import android.widget.ListAdapter;
import androidx.appcompat.app.AlertDialog;

/**
 * The app's dialog builder: an {@link AlertDialog.Builder} that remembers what it was given, so
 * that in the Liquid Glass design {@link #create()} can show the same dialog as glass (an alert, or
 * a sheet for lists) instead of a One UI dialog. In the other designs it is a
 * plain AlertDialog.Builder.
 */
final class AppDialog extends AlertDialog.Builder {
    CharSequence title;
    CharSequence message;
    CharSequence positiveText;
    CharSequence negativeText;
    CharSequence neutralText;
    DialogInterface.OnClickListener positiveListener;
    DialogInterface.OnClickListener negativeListener;
    DialogInterface.OnClickListener neutralListener;
    CharSequence[] items;
    ListAdapter adapter;
    DialogInterface.OnClickListener itemsListener;
    boolean singleChoice;
    int checkedItem = -1;
    boolean multiChoice;
    boolean[] checkedItems;
    DialogInterface.OnMultiChoiceClickListener multiChoiceListener;
    View view;
    DialogInterface.OnDismissListener dismissListener;
    DialogInterface.OnCancelListener cancelListener;

    AppDialog(Context context) {
        super(context);
    }

    private CharSequence text(int id) {
        return getContext().getText(id);
    }

    @Override
    public AppDialog setTitle(CharSequence value) {
        title = value;
        super.setTitle(value);
        return this;
    }

    @Override
    public AppDialog setTitle(int id) {
        return setTitle(text(id));
    }

    @Override
    public AppDialog setMessage(CharSequence value) {
        message = value;
        super.setMessage(value);
        return this;
    }

    @Override
    public AppDialog setMessage(int id) {
        return setMessage(text(id));
    }

    @Override
    public AppDialog setPositiveButton(CharSequence text, DialogInterface.OnClickListener l) {
        positiveText = text;
        positiveListener = l;
        super.setPositiveButton(text, l);
        return this;
    }

    @Override
    public AppDialog setPositiveButton(int id, DialogInterface.OnClickListener l) {
        return setPositiveButton(text(id), l);
    }

    @Override
    public AppDialog setNegativeButton(CharSequence text, DialogInterface.OnClickListener l) {
        negativeText = text;
        negativeListener = l;
        super.setNegativeButton(text, l);
        return this;
    }

    @Override
    public AppDialog setNegativeButton(int id, DialogInterface.OnClickListener l) {
        return setNegativeButton(text(id), l);
    }

    @Override
    public AppDialog setNeutralButton(CharSequence text, DialogInterface.OnClickListener l) {
        neutralText = text;
        neutralListener = l;
        super.setNeutralButton(text, l);
        return this;
    }

    @Override
    public AppDialog setNeutralButton(int id, DialogInterface.OnClickListener l) {
        return setNeutralButton(text(id), l);
    }

    @Override
    public AppDialog setItems(CharSequence[] values, DialogInterface.OnClickListener l) {
        items = values;
        itemsListener = l;
        super.setItems(values, l);
        return this;
    }

    @Override
    public AppDialog setAdapter(ListAdapter value, DialogInterface.OnClickListener l) {
        adapter = value;
        itemsListener = l;
        super.setAdapter(value, l);
        return this;
    }

    @Override
    public AppDialog setSingleChoiceItems(CharSequence[] values, int checked,
            DialogInterface.OnClickListener l) {
        items = values;
        checkedItem = checked;
        singleChoice = true;
        itemsListener = l;
        super.setSingleChoiceItems(values, checked, l);
        return this;
    }

    @Override
    public AppDialog setMultiChoiceItems(CharSequence[] values, boolean[] checked,
            DialogInterface.OnMultiChoiceClickListener l) {
        items = values;
        checkedItems = checked != null ? checked : new boolean[values.length];
        multiChoice = true;
        multiChoiceListener = l;
        super.setMultiChoiceItems(values, checked, l);
        return this;
    }

    @Override
    public AppDialog setView(View value) {
        view = value;
        super.setView(value);
        return this;
    }

    @Override
    public AppDialog setOnDismissListener(DialogInterface.OnDismissListener l) {
        dismissListener = l;
        super.setOnDismissListener(l);
        return this;
    }

    @Override
    public AppDialog setOnCancelListener(DialogInterface.OnCancelListener l) {
        cancelListener = l;
        super.setOnCancelListener(l);
        return this;
    }

    @Override
    public AlertDialog create() {
        GlassApp glass = GlassApp.of(getContext());
        // Custom views stay One UI dialogs: the glass sheet has no place for Android views.
        if (glass != null && view == null) {
            return new GlassDialog(getContext(), glass, this);
        }
        return super.create();
    }

    @Override
    public AlertDialog show() {
        AlertDialog dialog = create();
        dialog.show();
        return dialog;
    }
}
