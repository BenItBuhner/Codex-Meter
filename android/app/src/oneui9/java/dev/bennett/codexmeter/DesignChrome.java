package dev.bennett.codexmeter;

import android.app.Activity;
import android.content.res.Resources;
import android.graphics.Insets;
import android.os.Build;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import androidx.appcompat.widget.Toolbar;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.view.ViewCompat;
import androidx.core.widget.NestedScrollView;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.appbar.CollapsingToolbarLayout;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.navigation.NavigationBarView;
import com.google.android.material.oneui.floatingactioncontainer.FloatingToolbarLayout;
import com.google.android.material.tabs.TabLayout;
import dev.oneuiproject.oneui.widget.BottomTabLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;

/**
 * One UI 9 preview: puts SESL9's own chrome on top of the One UI 8 oneui-design wrappers.
 *
 * <ul>
 *   <li>Each collapsing app bar's toolbar moves into SESL9's {@link FloatingToolbarLayout}, the
 *       floating pill toolbar One UI 9 shows once the large title scrolls away.</li>
 *   <li>Scrolling lists get SESL9's native top and bottom fading edges.</li>
 *   <li>The provider tabs become SESL9's floating, blurred {@link BottomNavigationView}.</li>
 * </ul>
 *
 * <p>Adapted from Twidget's SeslToolbarCompatibility (MIT, Josh Skinner), which bridges the same
 * oneui8 wrapper to SESL9's sibling floating-toolbar contract.
 */
final class DesignChrome {
    private static final int INSTALLED_TAG = R.id.design_chrome_installed;

    private DesignChrome() {
    }

    static void install(Activity activity) {
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null || content.getTag(INSTALLED_TAG) != null) {
            return;
        }
        content.setTag(INSTALLED_TAG, Boolean.TRUE);
        WeakHashMap<View, Boolean> faded = new WeakHashMap<>();
        Runnable apply = () -> {
            enableNativeFadingEdges(content, faded);
            for (AppBarLayout appBar : descendants(content, AppBarLayout.class)) {
                installFloatingToolbar(appBar);
            }
            BottomTabLayout tabs = first(content, BottomTabLayout.class);
            if (tabs != null) {
                FloatingTabs.attach(activity, content, tabs);
            }
        };
        apply.run();
        // Settings fragments, sign-in pages and rebuilt dashboards attach their views later.
        content.getViewTreeObserver().addOnGlobalLayoutListener(apply::run);
    }

    private static void enableNativeFadingEdges(ViewGroup root, WeakHashMap<View, Boolean> faded) {
        for (View view : descendants(root, View.class)) {
            if (faded.containsKey(view)) {
                continue;
            }
            if (view instanceof NestedScrollView) {
                ((NestedScrollView) view).seslSetFadingEdgeEnabled(true);
            } else if (view instanceof RecyclerView) {
                ((RecyclerView) view).seslSetFadingEdgeEnabled(true);
            } else {
                continue;
            }
            faded.put(view, Boolean.TRUE);
            // A list can get its first insets before the fade helper installs its listener.
            ViewCompat.requestApplyInsets(view);
        }
    }

    private static void installFloatingToolbar(AppBarLayout appBar) {
        if (!(appBar.getParent() instanceof CoordinatorLayout)) {
            return;
        }
        CoordinatorLayout coordinator = (CoordinatorLayout) appBar.getParent();
        CollapsingToolbarLayout collapsing = firstChild(appBar, CollapsingToolbarLayout.class);
        if (collapsing == null || firstChild(coordinator, FloatingToolbarLayout.class) != null) {
            return;
        }
        Toolbar toolbar = firstChild(collapsing, Toolbar.class);
        if (toolbar == null) {
            return;
        }
        if (appBar.getId() == View.NO_ID) {
            appBar.setId(View.generateViewId());
        }
        float density = appBar.getResources().getDisplayMetrics().density;
        int toolbarHeight = toolbar.getLayoutParams().height;
        float collapsedToolbarHeight = Math.max(appBar.seslGetCollapsedHeight(), toolbarHeight);

        FloatingToolbarLayout floating = new FloatingToolbarLayout(appBar.getContext());
        floating.setId(View.generateViewId());
        floating.enableScrollTransition(false, true);
        floating.enableToolbarItemBackgroundTransition(true);
        floating.setElevation(Math.max(appBar.getElevation(), 4 * density));
        CoordinatorLayout.LayoutParams params = new CoordinatorLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setAnchorId(appBar.getId());
        // Anchored TOP places the floating bar's bottom edge on the app bar's top edge... so the
        // pair below keeps it pinned to the top of the window as the app bar scrolls away.
        params.anchorGravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        coordinator.addView(floating, params);

        Runnable moveToolbars = () -> {
            // Keep the same Toolbar objects so navigation, menus and the wrapper's
            // search/selection bars keep working; they just render inside the floating pill.
            for (Toolbar bar : children(collapsing, Toolbar.class)) {
                int height = bar.getLayoutParams().height;
                collapsing.removeView(bar);
                if (height > 0) {
                    bar.setMinimumHeight(Math.max(bar.getMinimumHeight(), height));
                }
                floating.addView(bar, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            if (toolbarHeight > 0) {
                collapsing.setMinimumHeight(toolbarHeight);
            }
            float collapsedHeight = collapsedToolbarHeight + appBar.getPaddingTop();
            if (appBar.seslGetCollapsedHeight() != collapsedHeight) {
                appBar.seslSetCollapsedHeight(collapsedHeight);
                appBar.requestLayout();
            }
        };
        moveToolbars.run();
        // The wrapper inflates its search and selection toolbars lazily.
        collapsing.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> moveToolbars.run());

        View[] bound = new View[1];
        floating.getViewTreeObserver().addOnPreDrawListener(() -> {
            // Link the floating bar to the visible scroller so SESL9 can clamp its top fade while
            // the app bar moves off screen.
            View scroll = firstShownScroller(coordinator);
            if (scroll != null && scroll != bound[0]) {
                bound[0] = scroll;
                if (scroll instanceof NestedScrollView) {
                    floating.setNestedScrollView((NestedScrollView) scroll);
                } else {
                    floating.setRecyclerView((RecyclerView) scroll);
                }
            }
            return true;
        });
    }

    private static View firstShownScroller(ViewGroup root) {
        for (View view : descendants(root, View.class)) {
            if (view.isShown() && (view instanceof NestedScrollView || view instanceof RecyclerView)) {
                return view;
            }
        }
        return null;
    }

    /**
     * Mirrors the oneui-design {@link BottomTabLayout} (which keeps owning the provider menu, the
     * "More" grid and the click handling) into a floating SESL9 bottom navigation bar.
     */
    private static final class FloatingTabs {
        private final BottomTabLayout tabs;
        private final BottomNavigationView nav;
        private final ViewGroup content;
        private String signature = "";
        private int bottomInset;
        private boolean syncing;

        private FloatingTabs(Activity activity, ViewGroup content, BottomTabLayout tabs) {
            this.tabs = tabs;
            this.content = content;
            this.nav = new BottomNavigationView(activity, null, 0,
                    Ui.isDark(activity)
                            ? R.style.Widget_Design_BottomNavigationView_Floating_IconText
                            : R.style.Widget_Design_Light_BottomNavigationView_Floating_IconText);
            nav.setId(R.id.floating_provider_tabs);
            nav.setLabelVisibilityMode(NavigationBarView.LABEL_VISIBILITY_LABELED);
            nav.setItemHorizontalTranslationEnabled(false);
            nav.setOnItemSelectedListener(this::onItemSelected);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            content.addView(nav, params);
            nav.setOnApplyWindowInsetsListener((view, insets) -> {
                if (Build.VERSION.SDK_INT >= 30) {
                    Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                    bottomInset = bars.bottom;
                } else {
                    bottomInset = insets.getSystemWindowInsetBottom();
                }
                applyMargins();
                return insets;
            });
            tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
                @Override
                public void onTabSelected(TabLayout.Tab tab) {
                    syncSelection();
                }

                @Override
                public void onTabUnselected(TabLayout.Tab tab) {
                }

                @Override
                public void onTabReselected(TabLayout.Tab tab) {
                }
            });
            tabs.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    return sync();
                }
            });
        }

        static void attach(Activity activity, ViewGroup content, BottomTabLayout tabs) {
            if (tabs.getTag(R.id.floating_provider_tabs) == null) {
                tabs.setTag(R.id.floating_provider_tabs, new FloatingTabs(activity, content, tabs));
            }
        }

        /** Rebuilds the floating menu when the providers, their order or visibility change. */
        private boolean sync() {
            // Same rule MainActivity uses for the stock tabs; the stock bar itself stays out of
            // the layout.
            boolean wanted = ProviderTabs.showsBar(
                    ProviderPreferences.getEnabled(nav.getContext()).size());
            StringBuilder next = new StringBuilder().append(wanted);
            for (int i = 0; i < tabs.getTabCount(); i++) {
                TabLayout.Tab tab = tabs.getTabAt(i);
                next.append('|').append(tab == null ? "" : tab.getText());
            }
            if (next.toString().equals(signature)) {
                return true;
            }
            signature = next.toString();
            Menu menu = nav.getMenu();
            menu.clear();
            int count = Math.min(tabs.getTabCount(), nav.getMaxItemCount());
            for (int i = 0; i < count; i++) {
                TabLayout.Tab tab = tabs.getTabAt(i);
                if (tab == null) {
                    continue;
                }
                MenuItem item = menu.add(Menu.NONE, i + 1, i, tab.getText());
                item.setIcon(tab.getIcon());
            }
            nav.setVisibility(wanted && count > 1 ? View.VISIBLE : View.GONE);
            syncSelection();
            applyMargins();
            return true;
        }

        private void syncSelection() {
            int position = tabs.getSelectedTabPosition();
            if (position >= 0 && position < nav.getMenu().size()) {
                syncing = true;
                nav.setSelectedItemId(position + 1);
                syncing = false;
            }
        }

        private boolean onItemSelected(MenuItem item) {
            if (syncing) {
                return true;
            }
            TabLayout.Tab tab = tabs.getTabAt(item.getItemId() - 1);
            if (tab != null) {
                // A click runs the same path as tapping the stock tab, including "More".
                tab.view.performClick();
            }
            // "More" opens a grid; the provider picked there updates the selection afterwards.
            return tab != null && tab.getId() != dev.oneuiproject.oneui.design.R.id.bottom_tab_menu_show_grid_dialog;
        }

        private void applyMargins() {
            Resources res = nav.getResources();
            int gap = Math.round(16 * res.getDisplayMetrics().density);
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) nav.getLayoutParams();
            if (params.bottomMargin != bottomInset + gap) {
                params.bottomMargin = bottomInset + gap;
                nav.setLayoutParams(params);
            }
            // Keep the stock tab bar out of the layout so content runs under the floating bar,
            // and leave room below the last card to scroll it clear.
            if (tabs.getVisibility() != View.GONE) {
                tabs.setVisibility(View.GONE);
            }
            NestedScrollView scroll = first(content, NestedScrollView.class);
            if (scroll != null) {
                int reserve = nav.getVisibility() == View.VISIBLE
                        ? res.getDimensionPixelSize(
                                R.dimen.sesl_bottom_navigation_floating_height)
                                + gap * 2
                        : 0;
                if (scroll.getPaddingBottom() != reserve) {
                    scroll.setPadding(scroll.getPaddingLeft(), scroll.getPaddingTop(),
                            scroll.getPaddingRight(), reserve);
                }
            }
        }
    }

    // ---- view tree helpers ----

    private static <T extends View> List<T> descendants(ViewGroup root, Class<T> type) {
        List<T> out = new ArrayList<>();
        collect(root, type, out);
        return out;
    }

    private static <T extends View> void collect(ViewGroup group, Class<T> type, List<T> out) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (type.isInstance(child)) {
                out.add(type.cast(child));
            }
            if (child instanceof ViewGroup) {
                collect((ViewGroup) child, type, out);
            }
        }
    }

    private static <T extends View> T first(ViewGroup root, Class<T> type) {
        List<T> all = descendants(root, type);
        return all.isEmpty() ? null : all.get(0);
    }

    private static <T extends View> List<T> children(ViewGroup group, Class<T> type) {
        List<T> out = new ArrayList<>();
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (type.isInstance(child)) {
                out.add(type.cast(child));
            }
        }
        return out;
    }

    private static <T extends View> T firstChild(ViewGroup group, Class<T> type) {
        List<T> all = children(group, type);
        return all.isEmpty() ? null : all.get(0);
    }
}
