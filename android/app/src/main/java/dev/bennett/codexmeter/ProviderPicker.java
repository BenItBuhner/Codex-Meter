package dev.bennett.codexmeter;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import dev.oneuiproject.oneui.widget.RoundedLinearLayout;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The provider list shared by Settings → Accounts → Add account and the setup screen: a search
 * field over every provider not yet connected, in recommended order. Each row shows the
 * provider's mark, what it meters and how it signs in, and flags experimental providers.
 * Single-select mode reports a tap; multi-select mode shows checkboxes.
 */
public final class ProviderPicker {
    public interface Listener {
        void onPicked(UsageProvider provider);
    }

    private final Context context;
    private final boolean dark;
    private final boolean multiSelect;
    private final Collection<UsageProvider> hidden;
    private final List<UsageProvider> selected;
    private final Listener listener;
    private final LinearLayout root;
    private final RoundedLinearLayout list;
    private final TextView empty;

    /**
     * @param hidden providers to leave out (already connected)
     * @param selected multi-select state, updated in place; null for single select
     */
    public ProviderPicker(Context context, boolean dark, Collection<UsageProvider> hidden,
            List<UsageProvider> selected, Listener listener) {
        this.context = context;
        this.dark = dark;
        this.multiSelect = selected != null;
        this.hidden = hidden == null ? new ArrayList<>() : hidden;
        this.selected = selected;
        this.listener = listener;
        this.root = new LinearLayout(context);
        this.root.setOrientation(LinearLayout.VERTICAL);
        this.root.addView(buildSearch());
        Ui.addSpacer(this.root, 16);
        this.list = Ui.seslRowCard(context, dark);
        this.root.addView(this.list);
        this.empty = Ui.text(context, "No providers match.", 14.0f, Ui.secondaryText(dark));
        this.empty.setGravity(Gravity.CENTER);
        this.empty.setPadding(0, Ui.dp(context, 24), 0, Ui.dp(context, 24));
        filter("");
    }

    public View view() {
        return root;
    }

    private View buildSearch() {
        LinearLayout box = Ui.horizontal(context, Gravity.CENTER_VERTICAL);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Ui.cardColor(context, dark));
        background.setCornerRadius(Ui.dp(context, 26));
        box.setBackground(background);
        box.setPadding(Ui.dp(context, 18), 0, Ui.dp(context, 14), 0);
        box.setMinimumHeight(Ui.dp(context, 52));

        ImageView icon = new ImageView(context);
        icon.setImageResource(R.drawable.ic_oui_search);
        icon.setImageTintList(ColorStateList.valueOf(Ui.secondaryText(dark)));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        box.addView(icon, new LinearLayout.LayoutParams(Ui.dp(context, 22), Ui.dp(context, 22)));

        EditText field = new EditText(context);
        field.setHint("Search providers");
        field.setSingleLine(true);
        field.setBackground(null);
        field.setTextSize(16.0f);
        field.setTextColor(Ui.mainText(dark));
        field.setHintTextColor(Ui.secondaryText(dark));
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        field.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
                filter(text.toString());
            }

            @Override
            public void afterTextChanged(Editable text) {
            }
        });
        LinearLayout.LayoutParams fieldParams = new LinearLayout.LayoutParams(0, -2, 1.0f);
        fieldParams.setMargins(Ui.dp(context, 10), 0, 0, 0);
        box.addView(field, fieldParams);
        box.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        return box;
    }

    private void filter(String query) {
        list.removeAllViews();
        List<UsageProvider> providers = ProviderCatalog.pickerProviders(hidden, query);
        if (providers.isEmpty()) {
            list.addView(empty);
            return;
        }
        for (int i = 0; i < providers.size(); i++) {
            list.addView(buildRow(providers.get(i)));
            if (i < providers.size() - 1) {
                View divider = new View(context);
                divider.setBackgroundColor(Ui.divider(dark));
                LinearLayout.LayoutParams dividerParams =
                        new LinearLayout.LayoutParams(-1, Math.max(1, Ui.dp(context, 0.5f)));
                dividerParams.setMargins(Ui.dp(context, 66), 0, Ui.dp(context, 20), 0);
                list.addView(divider, dividerParams);
            }
        }
    }

    private View buildRow(UsageProvider provider) {
        ProviderCatalog.Entry entry = ProviderCatalog.get(provider);
        LinearLayout row = Ui.horizontal(context, Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Ui.dp(context, 72));
        row.setPadding(Ui.dp(context, 20), Ui.dp(context, 10), Ui.dp(context, 16),
                Ui.dp(context, 10));
        row.setClickable(true);
        row.setFocusable(true);
        android.util.TypedValue ripple = new android.util.TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        row.setBackgroundResource(ripple.resourceId);

        ImageView icon = new ImageView(context);
        icon.setImageResource(ProvidersActivity.iconRes(provider));
        icon.setImageTintList(ColorStateList.valueOf(Ui.mainText(dark)));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(
                Ui.dp(context, 26), Ui.dp(context, 26));
        iconParams.setMargins(0, 0, Ui.dp(context, 20), 0);
        row.addView(icon, iconParams);

        LinearLayout labels = new LinearLayout(context);
        labels.setOrientation(LinearLayout.VERTICAL);
        LinearLayout titleLine = Ui.horizontal(context, Gravity.CENTER_VERTICAL);
        titleLine.addView(Ui.text(context, provider.displayName, 17.0f, Ui.mainText(dark)));
        if (entry.inferred) {
            TextView tag = Ui.text(context, "Experimental", 11.0f, Ui.accent(context, dark));
            tag.setTypeface(Ui.mediumTypeface(context));
            GradientDrawable pill = new GradientDrawable();
            pill.setCornerRadius(Ui.dp(context, 10));
            pill.setStroke(Math.max(1, Ui.dp(context, 1)), Ui.accent(context, dark));
            tag.setBackground(pill);
            tag.setPadding(Ui.dp(context, 7), Ui.dp(context, 1), Ui.dp(context, 7),
                    Ui.dp(context, 2));
            LinearLayout.LayoutParams tagParams = new LinearLayout.LayoutParams(-2, -2);
            tagParams.setMargins(Ui.dp(context, 8), 0, 0, 0);
            titleLine.addView(tag, tagParams);
        }
        labels.addView(titleLine);
        TextView summary = Ui.text(context, entry.pickerSummary(), 13.0f,
                Ui.secondaryText(dark));
        LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(-2, -2);
        summaryParams.setMargins(0, Ui.dp(context, 2), 0, 0);
        labels.addView(summary, summaryParams);
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1.0f));

        if (multiSelect) {
            CheckBox check = Ui.checkbox(context, "", selected.contains(provider), dark);
            check.setContentDescription("Track " + provider.displayName);
            check.setOnCheckedChangeListener((button, on) -> {
                if (on && !selected.contains(provider)) selected.add(provider);
                if (!on) selected.remove(provider);
                if (listener != null) listener.onPicked(provider);
            });
            row.addView(check, new LinearLayout.LayoutParams(-2, -2));
            row.setOnClickListener(view -> check.toggle());
        } else {
            ImageView chevron = new ImageView(context);
            chevron.setImageResource(R.drawable.ic_oui_keyboard_arrow_right);
            chevron.setImageTintList(ColorStateList.valueOf(Ui.secondaryText(dark)));
            chevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(chevron, new LinearLayout.LayoutParams(Ui.dp(context, 20),
                    Ui.dp(context, 20)));
            row.setContentDescription("Add " + provider.displayName);
            row.setOnClickListener(view -> {
                if (listener != null) listener.onPicked(provider);
            });
        }
        return row;
    }
}
