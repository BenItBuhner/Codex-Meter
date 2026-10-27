package dev.bennett.codexmeter;

import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The dashboard's provider switcher in the One UI 9 design: SESL9's floating, blurred
 * {@link BottomNavigationView}, floating over the content above the navigation bar.
 *
 * <p>It follows the same rule as the classic tabs ({@link ProviderTabs}): no bar for a single
 * provider, a tab each for up to four, and with five or more the first three plus "More", which
 * lists the rest.
 *
 * <p>Its blur is Samsung's per-view background blur, which samples what is behind the bar only
 * when the bar itself redraws. Scrolling the content underneath does not redraw the bar, so the
 * blur would keep showing whatever was there when it last drew. SESL's floating toolbar handles
 * this for its own pills by invalidating them on scroll; the bar does the same here.
 */
final class OneUi9ProviderBar {
    private static final int MORE_ID = 1000;

    private final OneUi9Page page;
    private final BottomNavigationView nav;
    private final Consumer<UsageProvider> onSelected;
    private final int gap;
    private List<UsageProvider> providers = new ArrayList<>();
    private UsageProvider selected;
    private boolean syncing;

    OneUi9ProviderBar(OneUi9Page page, Consumer<UsageProvider> onSelected) {
        this.page = page;
        this.onSelected = onSelected;
        this.gap = Ui.dp(page.activity, 16.0f);
        this.nav = (BottomNavigationView) LayoutInflater.from(page.activity)
                .inflate(R.layout.oneui9_provider_bar, page.root, false);
        page.root.addView(nav);
        nav.setOnItemSelectedListener(this::onItemSelected);
        nav.setOnItemReselectedListener(item -> {
            if (item.getItemId() == MORE_ID) {
                showMore();
            }
        });
        ViewCompat.setOnApplyWindowInsetsListener(nav, (view, insets) -> {
            int bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
            CoordinatorLayout.LayoutParams params =
                    (CoordinatorLayout.LayoutParams) view.getLayoutParams();
            if (params.bottomMargin != bottom + gap) {
                params.bottomMargin = bottom + gap;
                view.setLayoutParams(params);
            }
            return insets;
        });
        nav.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> reserveRoom());
        keepBlurCurrent();
    }

    /**
     * Redraws the bar whenever the content under it moves, so its blur samples the new content.
     * Scrolls of any view on the page count, and so do layout passes (cards refreshing or
     * resizing) and the large title collapsing, which moves the body without scrolling it.
     */
    private void keepBlurCurrent() {
        ViewTreeObserver.OnScrollChangedListener onScroll = this::refreshBlur;
        ViewTreeObserver.OnGlobalLayoutListener onLayout = this::refreshBlur;
        nav.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View view) {
                ViewTreeObserver observer = view.getViewTreeObserver();
                observer.addOnScrollChangedListener(onScroll);
                observer.addOnGlobalLayoutListener(onLayout);
                refreshBlur();
            }

            @Override
            public void onViewDetachedFromWindow(View view) {
                ViewTreeObserver observer = view.getViewTreeObserver();
                observer.removeOnScrollChangedListener(onScroll);
                observer.removeOnGlobalLayoutListener(onLayout);
            }
        });
        if (nav.isAttachedToWindow()) {
            ViewTreeObserver observer = nav.getViewTreeObserver();
            observer.addOnScrollChangedListener(onScroll);
            observer.addOnGlobalLayoutListener(onLayout);
        }
        page.appBar.addOnOffsetChangedListener(
                (AppBarLayout.OnOffsetChangedListener) (bar, offset) -> refreshBlur());
    }

    private void refreshBlur() {
        if (nav.getVisibility() == View.VISIBLE) {
            nav.invalidate();
        }
    }

    /** Shows {@code enabled} providers in order with {@code current} selected. */
    void update(List<UsageProvider> enabled, UsageProvider current) {
        if (!enabled.equals(providers)) {
            providers = new ArrayList<>(enabled);
            Menu menu = nav.getMenu();
            menu.clear();
            int own = ProviderTabs.ownTabs(enabled.size());
            for (int i = 0; i < own; i++) {
                UsageProvider provider = enabled.get(i);
                // SESL moves items without SHOW_AS_ACTION_ALWAYS into its own overflow.
                menu.add(Menu.NONE, i + 1, i, provider.displayName)
                        .setIcon(ProvidersActivity.iconRes(provider))
                        .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
            }
            if (ProviderTabs.showsMore(enabled.size())) {
                menu.add(Menu.NONE, MORE_ID, own, R.string.oneui9_more_providers)
                        .setIcon(R.drawable.ic_oui_apps_outline)
                        .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
            }
            menu.setGroupCheckable(Menu.NONE, true, true);
            nav.setVisibility(ProviderTabs.showsBar(enabled.size()) ? View.VISIBLE : View.GONE);
            selected = null;
        }
        select(current);
        reserveRoom();
    }

    private void select(UsageProvider provider) {
        if (provider == selected && provider != null) {
            return;
        }
        selected = provider;
        int index = providers.indexOf(provider);
        int own = ProviderTabs.ownTabs(providers.size());
        int id = index < 0 ? 0 : (index < own ? index + 1 : MORE_ID);
        MenuItem item = id == 0 ? null : nav.getMenu().findItem(id);
        if (item == null) {
            return;
        }
        syncing = true;
        item.setChecked(true);
        nav.setSelectedItemId(id);
        syncing = false;
        nav.post(this::styleLabels);
    }

    private boolean onItemSelected(MenuItem item) {
        if (syncing) {
            return true;
        }
        if (item.getItemId() == MORE_ID) {
            showMore();
            // The selection follows the provider picked in the list.
            return false;
        }
        int index = item.getItemId() - 1;
        if (index >= 0 && index < providers.size()) {
            selected = providers.get(index);
            onSelected.accept(selected);
            nav.post(this::styleLabels);
            return true;
        }
        return false;
    }

    private void showMore() {
        int own = ProviderTabs.ownTabs(providers.size());
        List<UsageProvider> rest = providers.subList(own, providers.size());
        CharSequence[] names = new CharSequence[rest.size()];
        int checked = -1;
        for (int i = 0; i < rest.size(); i++) {
            names[i] = rest.get(i).displayName;
            if (rest.get(i) == selected) {
                checked = i;
            }
        }
        new AlertDialog.Builder(page.activity)
                .setTitle(R.string.oneui9_more_providers)
                .setSingleChoiceItems(names, checked, (dialog, which) -> {
                    dialog.dismiss();
                    UsageProvider picked = rest.get(which);
                    selected = null;
                    select(picked);
                    onSelected.accept(picked);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** One UI bolds the selected tab's label; SESL9 keeps every label the same weight. */
    private void styleLabels() {
        Menu menu = nav.getMenu();
        for (int i = 0; i < menu.size(); i++) {
            MenuItem item = menu.getItem(i);
            View view = nav.findViewById(item.getItemId());
            if (view instanceof ViewGroup) {
                bold((ViewGroup) view, item.isChecked());
            }
        }
    }

    private static void bold(ViewGroup group, boolean checked) {
        int style = checked ? Typeface.BOLD : Typeface.NORMAL;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof TextView) {
                TextView label = (TextView) child;
                Typeface face = label.getTypeface();
                if (face == null || face.getStyle() != style) {
                    label.setTypeface(Typeface.create(face, style));
                }
            } else if (child instanceof ViewGroup) {
                bold((ViewGroup) child, checked);
            }
        }
    }

    /** Leaves room below the last card so it can scroll clear of the floating bar. */
    private void reserveRoom() {
        int reserve = nav.getVisibility() == View.VISIBLE && nav.getHeight() > 0
                ? nav.getHeight() + gap * 2
                : 0;
        page.setBottomReserve(reserve);
    }
}
