package dev.bennett.codexmeter;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.benitbuhner.liquidglass.widget.GlassMenu;
import com.benitbuhner.liquidglass.widget.GlassTabBar;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The dashboard's provider switcher in the Liquid Glass design: Glasse's iOS 26 floating tab bar.
 * Touching it lifts a clear lens over the tab, and dragging slides the lens between providers.
 *
 * <p>Same rule as the other designs ({@link ProviderTabs}): no bar for a single provider, a tab
 * each for up to four, and with five or more the first three plus "More", which opens Apple's
 * pull-down menu out of the More tab with the rest.
 */
final class GlassProviderBar implements ProviderBar {
    private final GlassPage page;
    private final GlassTabBar bar;
    /** An invisible stand-in over the More tab: the menu grows out of it. */
    private final View moreAnchor;
    private final Consumer<UsageProvider> onSelected;
    private final int gap;
    private List<UsageProvider> providers = new ArrayList<>();
    private UsageProvider selected;
    private GlassMenu moreMenu;

    GlassProviderBar(GlassPage page, Consumer<UsageProvider> onSelected) {
        this.page = page;
        this.onSelected = onSelected;
        this.gap = Ui.dp(page.activity, 12.0f);
        bar = new GlassTabBar(page.activity);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        int side = Ui.dp(page.activity, 20.0f);
        params.setMargins(side, 0, side, 0);
        page.root.addView(bar, params);
        moreAnchor = new View(page.activity);
        moreAnchor.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        page.root.addView(moreAnchor, new FrameLayout.LayoutParams(0, 0));
        bar.setOnTabSelectedListener((tabs, index) -> onTab(index));
        // A tap on More that is already selected still opens the list.
        bar.setOnClickListener(v -> {
            if (ProviderTabs.showsMore(providers.size())
                    && bar.getSelected() == ProviderTabs.ownTabs(providers.size())) {
                showMore();
            }
        });
        ViewCompat.setOnApplyWindowInsetsListener(bar, (view, insets) -> {
            int bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
            if (lp.bottomMargin != bottom + gap) {
                lp.bottomMargin = bottom + gap;
                view.setLayoutParams(lp);
            }
            return insets;
        });
        bar.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            reserveRoom();
            placeMoreAnchor();
        });
    }

    @Override
    public void update(List<UsageProvider> enabled, UsageProvider current) {
        if (!enabled.equals(providers)) {
            providers = new ArrayList<>(enabled);
            bar.clearTabs();
            int own = ProviderTabs.ownTabs(enabled.size());
            for (int i = 0; i < own; i++) {
                UsageProvider provider = enabled.get(i);
                bar.addTab(provider.displayName, ProvidersActivity.iconRes(provider));
            }
            if (ProviderTabs.showsMore(enabled.size())) {
                bar.addTab(page.activity.getString(R.string.oneui9_more_providers),
                        R.drawable.ic_oui_apps_outline);
            }
            bar.setVisibility(ProviderTabs.showsBar(enabled.size()) ? View.VISIBLE : View.GONE);
            selected = null;
            placeMoreAnchor();
        }
        select(current);
        reserveRoom();
    }

    private void select(UsageProvider provider) {
        selected = provider;
        int index = providers.indexOf(provider);
        if (index < 0) {
            return;
        }
        bar.setSelected(Math.min(index, ProviderTabs.ownTabs(providers.size())));
    }

    private void onTab(int index) {
        int own = ProviderTabs.ownTabs(providers.size());
        if (index >= own) {
            // More: the click listener opens the list; the bar keeps showing the current tab
            // until a provider is picked.
            return;
        }
        if (index < providers.size()) {
            selected = providers.get(index);
            onSelected.accept(selected);
        }
    }

    private void showMore() {
        if (moreMenu != null && moreMenu.isShowing()) {
            return;
        }
        int own = ProviderTabs.ownTabs(providers.size());
        List<GlassMenu.Item> rows = new ArrayList<>();
        for (UsageProvider provider : providers.subList(own, providers.size())) {
            rows.add(new GlassMenu.Item(provider.displayName,
                    page.activity.getDrawable(ProvidersActivity.iconRes(provider)), false, false,
                    () -> {
                        selected = provider;
                        select(provider);
                        onSelected.accept(provider);
                    }));
        }
        moreMenu = new GlassMenu(moreAnchor).setItems(rows).setWidthDp(230.0f)
                .setOnDismissListener(menu -> {
                    // Picking nothing returns the lens to the provider on screen.
                    if (selected != null) {
                        select(selected);
                    }
                });
        moreMenu.show();
    }

    /** Puts the menu's anchor over the More tab, so the menu grows out of it. */
    private void placeMoreAnchor() {
        int count = bar.getTabCount();
        if (count == 0 || bar.getWidth() == 0) {
            return;
        }
        float inset = Ui.dp(page.activity, 4.0f);
        float item = (bar.getWidth() - 2 * inset) / count;
        int size = Ui.dp(page.activity, 48.0f);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) moreAnchor.getLayoutParams();
        int left = (int) (bar.getLeft() + inset + item * (count - 1) + (item - size) / 2);
        int top = bar.getTop() + (bar.getHeight() - size) / 2;
        if (lp.width != size || lp.leftMargin != left || lp.topMargin != top) {
            lp.width = size;
            lp.height = size;
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.leftMargin = left - page.root.getPaddingLeft();
            lp.topMargin = top;
            moreAnchor.setLayoutParams(lp);
        }
    }

    /** Leaves room below the last card so it can scroll clear of the floating bar. */
    private void reserveRoom() {
        int reserve = bar.getVisibility() == View.VISIBLE && bar.getHeight() > 0
                ? bar.getHeight() + gap * 2
                : 0;
        page.setBottomReserve(reserve);
    }
}
