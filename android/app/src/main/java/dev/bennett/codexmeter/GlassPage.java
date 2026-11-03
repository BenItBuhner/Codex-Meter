package dev.bennett.codexmeter;

import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.PopupMenu;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.core.widget.NestedScrollView;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import com.benitbuhner.liquidglass.GlassLooks;
import com.benitbuhner.liquidglass.GlassVersion;
import com.benitbuhner.liquidglass.LiquidGlassLayout;
import com.benitbuhner.liquidglass.widget.GlassButton;
import com.benitbuhner.liquidglass.widget.GlassMenu;
import com.benitbuhner.liquidglass.widget.GlassMenuButton;
import com.benitbuhner.liquidglass.widget.GlassNavBar;
import com.benitbuhner.liquidglass.widget.GlassScrollEdge;
import com.benitbuhner.liquidglass.widget.LargeTitleView;
import java.util.ArrayList;
import java.util.List;

/**
 * The Liquid Glass page (Settings → Appearance → App design → Liquid Glass): Apple's iOS 26 page
 * chrome from the Glasse library ({@code com.benitbuhner.liquidglass}), with the WWDC25 look and
 * menu motion.
 *
 * <p>The page is one {@link LiquidGlassLayout}. Its first child, the body, is the backdrop: the
 * page's own content scrolls edge to edge under the status bar. Over it sit iOS's progressive
 * scroll edge and a transparent {@link GlassNavBar} whose back and action buttons are glass that
 * refracts the content scrolling under them. A large title scrolls with the content; once it has
 * gone under the bar, the bar's inline title fades in, like UIKit.
 *
 * <p>The activity's options menu becomes glass buttons: one item is a single button, more are
 * gathered behind a "…" button that opens a {@link GlassMenu}, which grows out of the button with
 * the WWDC25 spring.
 */
final class GlassPage implements Ui.Header {
    /** Nav bar row (44 dp buttons + 10 dp above them), below the status bar. */
    private static final float NAV_DP = 54.0f;

    final AppCompatActivity activity;
    final LiquidGlassLayout root;
    final FrameLayout body;
    final GlassNavBar nav;
    private final GlassScrollEdge edge;
    private final LargeTitleView largeTitle;
    private final boolean expandable;
    private final boolean dark;
    private final List<GlassButton> menuButtons = new ArrayList<>();
    private CharSequence title;
    private int topInset = -1;
    private int bottomInset;
    private int bottomReserve;
    private View boundScroller;
    private int scrollerBasePaddingTop;
    private int scrollerBasePaddingBottom;

    private GlassPage(AppCompatActivity activity, boolean expandable) {
        this.activity = activity;
        this.expandable = expandable;
        this.dark = Ui.isDark(activity);
        applyLook();
        root = new LiquidGlassLayout(activity);
        root.setDark(dark);
        root.setAccentColor(Ui.accent(activity, dark));
        root.setBackgroundColor(pageColor(activity));
        root.setId(R.id.glass_page_root);

        body = new FrameLayout(activity);
        body.setId(R.id.glass_page_body);
        root.addView(body, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        largeTitle = new LargeTitleView(activity);
        largeTitle.setSingleLine(true);
        largeTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        largeTitle.setPadding(Ui.dp(activity, 20.0f), Ui.dp(activity, 2.0f),
                Ui.dp(activity, 20.0f), Ui.dp(activity, 10.0f));
        largeTitle.setVisibility(expandable ? View.VISIBLE : View.GONE);

        edge = new GlassScrollEdge(activity);
        edge.setPageColor(pageColor(activity));
        root.addView(edge, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0,
                Gravity.TOP));

        nav = new GlassNavBar(activity);
        root.addView(nav, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));
        nav.setTitleVisible(!expandable);
    }

    /** Whether the Liquid Glass design is on. Switching it restarts the app. */
    static boolean isOn(Activity activity) {
        return AppPreferences.isLiquidGlassDesign(activity);
    }

    /** The WWDC25 render: Apple's keynote look and its springy menu motion. */
    static void applyLook() {
        if (GlassLooks.getVersion() != GlassVersion.WWDC25) {
            GlassLooks.setVersion(GlassVersion.WWDC25);
        }
    }

    /**
     * The page color under every card. It is SESL's round-and-background color (near iOS's
     * grouped background in both themes), because SESL paints its list cards' rounded corners in
     * that color; any other page color would show at the corners.
     */
    static int pageColor(Activity activity) {
        return activity.getColor(R.color.oui_des_round_and_bgcolor);
    }

    /**
     * Builds the page, puts {@code bodyLayout} in it and makes it the activity's content.
     *
     * @param expandable whether the page has a large title that scrolls away; widget settings use
     *     the inline title only, like their other designs.
     */
    static GlassPage install(AppCompatActivity activity, int bodyLayout, CharSequence title,
            boolean back, boolean expandable) {
        GlassPage page = new GlassPage(activity, expandable);
        LayoutInflater.from(activity).inflate(bodyLayout, page.body, true);
        page.body.addView(page.largeTitle, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP));
        activity.setContentView(page.root);
        page.setUp(title, back);
        return page;
    }

    private void setUp(CharSequence title, boolean back) {
        edgeToEdge();
        setTitle(title);
        setBack(back);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(bars.left, 0, bars.right, 0);
            applyTopInset(bars.top);
            bottomInset = bars.bottom;
            applyScrollerPadding();
            return insets;
        });
        // Fragments (Settings) and rebuilt pages attach their scrolling view later.
        root.getViewTreeObserver().addOnGlobalLayoutListener(this::bindScroller);
        root.getViewTreeObserver().addOnScrollChangedListener(this::onScrolled);
        largeTitle.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (b - t != ob - ot) {
                applyScrollerPadding();
            }
        });
        root.post(this::rebuildMenu);
        ViewCompat.requestApplyInsets(root);
    }

    /** Content runs under the status and navigation bars, as on iOS. */
    private void edgeToEdge() {
        Window window = activity.getWindow();
        WindowCompat.setDecorFitsSystemWindows(window, false);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 29) {
            window.setStatusBarContrastEnforced(false);
            window.setNavigationBarContrastEnforced(false);
        }
        WindowInsetsControllerCompat bars = WindowCompat.getInsetsController(window,
                window.getDecorView());
        bars.setAppearanceLightStatusBars(!dark);
        bars.setAppearanceLightNavigationBars(!dark);
    }

    private void applyTopInset(int top) {
        if (top == topInset) {
            return;
        }
        topInset = top;
        int navHeight = top + Ui.dp(activity, NAV_DP);
        // The scroll edge reaches a little below the bar, where iOS's soft edge ends.
        ViewGroup.LayoutParams edgeParams = edge.getLayoutParams();
        edgeParams.height = navHeight + Ui.dp(activity, 18.0f);
        edge.setLayoutParams(edgeParams);
        FrameLayout.LayoutParams titleParams =
                (FrameLayout.LayoutParams) largeTitle.getLayoutParams();
        titleParams.topMargin = navHeight;
        largeTitle.setLayoutParams(titleParams);
        applyScrollerPadding();
    }

    /** Extra room below the content, e.g. for the floating tab bar or Cancel / Save. */
    void setBottomReserve(int px) {
        if (bottomReserve != px) {
            bottomReserve = px;
            applyScrollerPadding();
        }
    }

    int bottomInset() {
        return bottomInset;
    }

    /** Height of everything above the content: status bar, nav bar and the large title. */
    private int headerHeight() {
        int top = Math.max(0, topInset) + Ui.dp(activity, NAV_DP);
        if (expandable) {
            top += largeTitle.getHeight();
        }
        return top;
    }

    /**
     * Binds the visible scrolling view: it scrolls under the bars (no clipping at its padding),
     * starts below the large title and ends clear of the navigation bar and tab bar.
     */
    private void bindScroller() {
        View scroller = firstScroller(body);
        if (scroller == null || scroller == boundScroller) {
            return;
        }
        boundScroller = scroller;
        scrollerBasePaddingTop = scroller.getPaddingTop();
        scrollerBasePaddingBottom = scroller.getPaddingBottom();
        if (scroller instanceof ViewGroup) {
            ((ViewGroup) scroller).setClipToPadding(false);
        }
        scroller.setVerticalFadingEdgeEnabled(false);
        if (scroller instanceof NestedScrollView) {
            ((NestedScrollView) scroller).seslSetFadingEdgeEnabled(false);
        } else if (scroller instanceof RecyclerView) {
            ((RecyclerView) scroller).seslSetFadingEdgeEnabled(false);
        }
        applyScrollerPadding();
        onScrolled();
    }

    private void applyScrollerPadding() {
        View scroller = boundScroller;
        if (scroller == null || topInset < 0) {
            return;
        }
        int top = scrollerBasePaddingTop + headerHeight();
        int bottom = scrollerBasePaddingBottom + bottomInset + bottomReserve;
        if (scroller.getPaddingTop() != top || scroller.getPaddingBottom() != bottom) {
            scroller.setPadding(scroller.getPaddingLeft(), top, scroller.getPaddingRight(),
                    bottom);
        }
        // Pull to refresh starts under the bar, not under the status bar.
        if (scroller.getParent() instanceof SwipeRefreshLayout) {
            SwipeRefreshLayout refresh = (SwipeRefreshLayout) scroller.getParent();
            int start = Math.max(0, topInset) + Ui.dp(activity, NAV_DP) - Ui.dp(activity, 40.0f);
            refresh.setProgressViewOffset(false, start, start + Ui.dp(activity, 72.0f));
        }
        onScrolled();
    }

    /** The large title moves with the content; the inline title shows once it is under the bar. */
    private void onScrolled() {
        View scroller = boundScroller;
        int offset = scrollOffset(scroller);
        if (expandable) {
            largeTitle.setTranslationY(-offset);
            int titleHeight = largeTitle.getHeight();
            nav.setTitleVisible(titleHeight > 0 && offset > titleHeight * 0.6f);
        }
        edge.setScrolled(offset > 0);
    }

    private static int scrollOffset(View scroller) {
        if (scroller instanceof RecyclerView) {
            return ((RecyclerView) scroller).computeVerticalScrollOffset();
        }
        return scroller == null ? 0 : scroller.getScrollY();
    }

    private static View firstScroller(View view) {
        if (view.getVisibility() != View.VISIBLE) {
            return null;
        }
        if (view instanceof NestedScrollView || view instanceof RecyclerView) {
            return view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = firstScroller(group.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    // ---- Options menu as glass buttons ----

    /**
     * Rebuilds the trailing glass buttons from the activity's options menu. A single item becomes
     * its own button; two or more go behind "…", which opens Apple's pull-down menu.
     */
    void rebuildMenu() {
        for (GlassButton button : menuButtons) {
            ViewGroup parent = (ViewGroup) button.getParent();
            if (parent != null) {
                parent.removeView(button);
            }
        }
        menuButtons.clear();
        Menu menu = new PopupMenu(activity, nav).getMenu();
        activity.onCreateOptionsMenu(menu);
        activity.onPrepareOptionsMenu(menu);
        List<MenuItem> items = new ArrayList<>();
        for (int i = 0; i < menu.size(); i++) {
            MenuItem item = menu.getItem(i);
            if (item.isVisible()) {
                items.add(item);
            }
        }
        if (items.isEmpty()) {
            return;
        }
        if (items.size() == 1) {
            MenuItem item = items.get(0);
            GlassButton button = new GlassButton(activity);
            if (item.getIcon() != null) {
                button.setIcon(item.getIcon());
                button.setContentDescription(item.getTitle());
            } else {
                button.setText(item.getTitle());
            }
            button.setEnabled(item.isEnabled());
            button.setOnClickListener(v -> activity.onOptionsItemSelected(item));
            addTrailing(button);
            return;
        }
        GlassMenuButton more = new GlassMenuButton(activity);
        List<GlassMenu.Item> rows = new ArrayList<>();
        for (MenuItem item : items) {
            rows.add(new GlassMenu.Item(item.getTitle(), item.getIcon(), false, false,
                    () -> activity.onOptionsItemSelected(item)));
        }
        more.getMenu().setItems(rows);
        addTrailing(more);
    }

    private void addTrailing(GlassButton button) {
        menuButtons.add(button);
        nav.addTrailing(button);
    }

    // ---- Ui.Header ----

    @Override
    public void setTitle(CharSequence title) {
        this.title = title;
        nav.setTitle(title);
        largeTitle.setText(title);
        activity.setTitle(title);
    }

    @Override
    public CharSequence getTitle() {
        return title;
    }

    @Override
    public void setBack(boolean back) {
        nav.setOnBackListener(back
                ? v -> activity.getOnBackPressedDispatcher().onBackPressed()
                : null);
    }
}
