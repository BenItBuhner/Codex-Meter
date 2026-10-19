package dev.bennett.codexmeter;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.EnumMap;
import java.util.Map;

/**
 * One UI 8.5-style floating bottom bar that switches the dashboard between usage providers:
 * a rounded capsule above the gesture bar, an icon over a short label for each provider, and a
 * pill highlight behind the selected one. One item is built per {@link UsageProvider}, so a new
 * provider only needs an icon in {@link #icon}.
 */
public final class ProviderSwitcherView extends FrameLayout {
    /** Called when the user picks a different provider. */
    public interface Listener {
        void onProviderSelected(UsageProvider provider);
    }

    private final Map<UsageProvider, Item> items = new EnumMap<>(UsageProvider.class);
    private final boolean dark;
    private UsageProvider selected = UsageProvider.DEFAULT;
    private Listener listener;

    private static final class Item {
        final LinearLayout root;
        final ImageView icon;
        final TextView label;

        Item(LinearLayout root, ImageView icon, TextView label) {
            this.root = root;
            this.icon = icon;
            this.label = label;
        }
    }

    public ProviderSwitcherView(Context context) {
        this(context, null);
    }

    public ProviderSwitcherView(Context context, AttributeSet attrs) {
        super(context, attrs);
        dark = Ui.isDark(context);
        setClipToPadding(false);
        setClipChildren(false);
        // Room around the capsule for its shadow, with the One UI side and bottom insets.
        setPadding(dp(28), dp(6), dp(28), dp(14));

        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(5), dp(5), dp(5), dp(5));
        GradientDrawable capsule = new GradientDrawable();
        capsule.setColor(Ui.cardColor(context, dark));
        capsule.setCornerRadius(dp(999));
        capsule.setStroke(Math.max(1, dp(0.5f)), Ui.divider(dark));
        bar.setBackground(capsule);
        bar.setElevation(dp(dark ? 2 : 6));
        addView(bar, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(62)));

        for (UsageProvider provider : UsageProvider.values()) {
            Item item = buildItem(context, provider);
            items.put(provider, item);
            bar.addView(item.root, new LinearLayout.LayoutParams(0,
                    LayoutParams.MATCH_PARENT, 1f));
        }
        applySelection();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** Shows {@code provider} as selected without notifying the listener. */
    public void setSelectedProvider(UsageProvider provider) {
        if (provider == null || provider == selected) {
            return;
        }
        selected = provider;
        applySelection();
    }

    public UsageProvider getSelectedProvider() {
        return selected;
    }

    private Item buildItem(Context context, UsageProvider provider) {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setClickable(true);
        root.setFocusable(true);
        root.setContentDescription(provider.displayName);
        root.setOnClickListener(view -> {
            if (provider == selected) {
                return;
            }
            selected = provider;
            applySelection();
            if (listener != null) {
                listener.onProviderSelected(provider);
            }
        });
        root.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host,
                    AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName("android.widget.TabWidget");
                info.setSelected(provider == selected);
            }
        });

        ImageView icon = new ImageView(context);
        icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        root.addView(icon, new LinearLayout.LayoutParams(dp(22), dp(22)));

        TextView label = new TextView(context);
        label.setText(provider.displayName);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        label.setSingleLine(true);
        label.setIncludeFontPadding(false);
        label.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        labelParams.topMargin = dp(3);
        root.addView(label, labelParams);
        return new Item(root, icon, label);
    }

    private void applySelection() {
        Context context = getContext();
        int primary = Ui.mainText(dark);
        int secondary = Ui.secondaryText(dark);
        for (Map.Entry<UsageProvider, Item> entry : items.entrySet()) {
            boolean on = entry.getKey() == selected;
            Item item = entry.getValue();
            item.root.setSelected(on);
            item.root.setBackground(itemBackground(context, on));
            Drawable icon = context.getDrawable(icon(entry.getKey(), on));
            if (icon != null) {
                icon = icon.mutate();
                icon.setTint(on ? primary : secondary);
            }
            item.icon.setImageDrawable(icon);
            item.label.setTextColor(on ? primary : secondary);
            item.label.setTypeface(on ? Ui.mediumTypeface(context)
                    : Ui.regularTypeface(context));
        }
    }

    /** Pill highlight for the selected item; a ripple-only pill for the rest. */
    private Drawable itemBackground(Context context, boolean on) {
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(dp(999));
        pill.setColor(on ? Ui.controlSurface(context, dark) : Color.TRANSPARENT);
        GradientDrawable mask = new GradientDrawable();
        mask.setCornerRadius(dp(999));
        mask.setColor(Color.WHITE);
        int ripple = Color.argb(dark ? 40 : 24, Color.red(Ui.mainText(dark)),
                Color.green(Ui.mainText(dark)), Color.blue(Ui.mainText(dark)));
        return new RippleDrawable(ColorStateList.valueOf(ripple),
                on ? pill : new ColorDrawable(Color.TRANSPARENT), mask);
    }

    /** One UI icon per provider: filled when selected, outline otherwise. */
    static int icon(UsageProvider provider, boolean selected) {
        switch (provider) {
            case CLAUDE_CODE:
                return selected ? R.drawable.ic_oui_star : R.drawable.ic_oui_star_outline;
            case CURSOR:
                return selected ? R.drawable.ic_oui_edit : R.drawable.ic_oui_edit_outline;
            case CODEX:
            default:
                return R.drawable.ic_oui_bracket;
        }
    }

    private int dp(float value) {
        return Ui.dp(getContext(), value);
    }
}
