package dev.bennett.codexmeter;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.core.widget.NestedScrollView;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.appbar.CollapsingToolbarLayout;
import com.google.android.material.oneui.floatingactioncontainer.FloatingToolbarLayout;

/**
 * The One UI 9 page (Settings → Appearance → One UI 9 design), built directly on SESL9 rather
 * than on oneui-design's One UI 8 {@code ToolbarLayout}.
 *
 * <p>Layout ({@code oneui9_page.xml}): a {@link CoordinatorLayout} holding a transparent
 * {@link AppBarLayout} with the large title, the page body under SESL's scrolling behavior, and
 * a {@link FloatingToolbarLayout} anchored to the app bar that carries the back button, the
 * small title and the action buttons. This is SESL9's own contract for One UI 9 pages:
 *
 * <ul>
 *   <li>Expanded: large title, plain toolbar buttons.</li>
 *   <li>Collapsed: small title in the toolbar row.</li>
 *   <li>Scrolled further: SESL9 hides the app bar, the title goes away and the toolbar buttons
 *       float in their own pill backgrounds (blurred on Samsung phones) while the content keeps
 *       scrolling under them and the status bar.</li>
 * </ul>
 *
 * <p>Nothing in this shell paints a background: the window color shows through everywhere and
 * the content fades out at the top and bottom with SESL9's native fading edge. That is what
 * keeps One UI 9 from showing a filled, rounded One UI 8 header box behind the floating buttons.
 */
final class OneUi9Page implements Ui.Header {
    final AppCompatActivity activity;
    final CoordinatorLayout root;
    final AppBarLayout appBar;
    final CollapsingToolbarLayout collapsing;
    final FloatingToolbarLayout floating;
    final Toolbar toolbar;
    final FrameLayout body;
    private final boolean expandable;
    private final int toolbarHeight;
    private final float baseCollapsedHeight;
    private int topInset = -1;
    private int bottomInset;
    private int bottomReserve;
    private View boundScroller;
    private View statusScrim;
    private int scrollerBasePaddingBottom;

    private OneUi9Page(AppCompatActivity activity, View root, boolean expandable) {
        this.activity = activity;
        this.root = (CoordinatorLayout) root;
        this.appBar = root.findViewById(R.id.oneui9_app_bar);
        this.collapsing = root.findViewById(R.id.oneui9_collapsing);
        this.floating = root.findViewById(R.id.oneui9_floating_toolbar);
        this.toolbar = root.findViewById(R.id.oneui9_toolbar);
        this.body = root.findViewById(R.id.oneui9_body);
        this.expandable = expandable;
        this.toolbarHeight = toolbar.getLayoutParams().height;
        this.baseCollapsedHeight = Math.max(appBar.seslGetCollapsedHeight(), toolbarHeight);
    }

    /** Whether the One UI 9 design is on. Switching it restarts the app, so it never changes under a live page. */
    static boolean isOn(Activity activity) {
        return AppPreferences.isOneUi9Design(activity);
    }

    /**
     * Builds the page, puts {@code bodyLayout} in it and makes it the activity's content.
     *
     * @param expandable whether the page has a large title that collapses on scroll; widget
     *     settings use a fixed, collapsed header like their classic version.
     */
    static OneUi9Page install(AppCompatActivity activity, int bodyLayout, CharSequence title,
            boolean back, boolean expandable) {
        ViewGroup parent = activity.findViewById(android.R.id.content);
        LayoutInflater inflater = LayoutInflater.from(activity);
        View root = inflater.inflate(R.layout.oneui9_page, parent, false);
        OneUi9Page page = new OneUi9Page(activity, root, expandable);
        inflater.inflate(bodyLayout, page.body, true);
        activity.setContentView(root);
        page.setUp(title, back);
        return page;
    }

    private void setUp(CharSequence title, boolean back) {
        edgeToEdge();
        addStatusScrim();
        activity.setSupportActionBar(toolbar);
        ActionBar actionBar = activity.getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayShowTitleEnabled(true);
        }
        setBack(back);
        setTitle(title);
        // A floating toolbar stays on screen; SESL9 decides when its buttons get pill backgrounds.
        floating.enableScrollTransition(false, true);
        floating.enableToolbarItemBackgroundTransition(true);
        if (expandable) {
            appBar.setEnabled(true);
            appBar.setExpanded(true, false);
        } else {
            appBar.setEnabled(false);
            appBar.setExpanded(false, false);
        }
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(bars.left, 0, bars.right, 0);
            applyTopInset(bars.top);
            bottomInset = bars.bottom;
            applyBottomPadding();
            return insets;
        });
        // Fragments (Settings) and rebuilt pages attach their scrolling view later.
        root.getViewTreeObserver().addOnGlobalLayoutListener(this::bindScroller);
        ViewCompat.requestApplyInsets(root);
    }

    /**
     * The page draws behind the status and navigation bars, as One UI 9 pages do: the content
     * runs under the status bar and fades there instead of stopping at a header edge.
     */
    private void edgeToEdge() {
        Window window = activity.getWindow();
        WindowCompat.setDecorFitsSystemWindows(window, false);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 29) {
            window.setStatusBarContrastEnforced(false);
            window.setNavigationBarContrastEnforced(false);
        }
        boolean dark = (activity.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        WindowInsetsControllerCompat bars = WindowCompat.getInsetsController(window,
                window.getDecorView());
        bars.setAppearanceLightStatusBars(!dark);
        bars.setAppearanceLightNavigationBars(!dark);
    }

    /**
     * Phones without One UI don't get SESL9's native top fade (it only renders on Samsung's
     * Android 16+ builds), so there a soft gradient in the window color over the status bar keeps
     * content from sitting hard against the clock. On One UI phones SESL's own fade does this and
     * nothing is added.
     */
    private void addStatusScrim() {
        if (androidx.reflect.DeviceInfo.isOneUI() && Build.VERSION.SDK_INT >= 36) {
            return;
        }
        int color = pageColor();
        int clear = color & 0x00FFFFFF;
        statusScrim = new View(activity);
        statusScrim.setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {color, color, clear}));
        statusScrim.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        CoordinatorLayout.LayoutParams params = new CoordinatorLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0);
        params.gravity = android.view.Gravity.TOP;
        // Above the content, below the floating toolbar.
        root.addView(statusScrim, root.indexOfChild(floating), params);
    }

    /**
     * Keeps the header's controls below the status bar while the page itself fills the window.
     * The collapsed stop includes the inset too, so content only reaches the toolbar once SESL9
     * enters its floating phase.
     */
    private void applyTopInset(int top) {
        if (top == topInset) {
            return;
        }
        topInset = top;
        appBar.setPadding(appBar.getPaddingLeft(), top, appBar.getPaddingRight(),
                appBar.getPaddingBottom());
        CoordinatorLayout.LayoutParams params =
                (CoordinatorLayout.LayoutParams) floating.getLayoutParams();
        params.topMargin = top;
        floating.setLayoutParams(params);
        if (statusScrim != null) {
            ViewGroup.LayoutParams scrim = statusScrim.getLayoutParams();
            scrim.height = top + Ui.dp(activity, 20.0f);
            statusScrim.setLayoutParams(scrim);
        }
        collapsing.setMinimumHeight(toolbarHeight);
        if (expandable) {
            // SESL sizes the expanded header as a share of the window height. The header here
            // also reaches under the status bar, so it gets the inset on top of that share;
            // otherwise the large title sits a status bar's height lower than on classic pages.
            appBar.seslSetProportionExtraHeight(top);
        }
        float collapsedHeight = baseCollapsedHeight + top;
        if (!expandable) {
            appBar.seslSetCustomHeight((int) collapsedHeight);
        }
        appBar.seslSetCollapsedHeight(collapsedHeight);
        appBar.requestLayout();
    }

    /** Extra room below the content, e.g. for a floating tab bar or Cancel / Save bar. */
    void setBottomReserve(int px) {
        if (bottomReserve != px) {
            bottomReserve = px;
            applyBottomPadding();
        }
    }

    int bottomInset() {
        return bottomInset;
    }

    /**
     * Binds the visible scrolling view: SESL9's native fading edge, the floating toolbar's
     * scroll tracking (which decides when the app bar may hide) and bottom padding so the last
     * card can scroll clear of the navigation bar.
     */
    private void bindScroller() {
        View scroller = firstScroller(body);
        if (scroller == null || scroller == boundScroller) {
            return;
        }
        boundScroller = scroller;
        scrollerBasePaddingBottom = scroller.getPaddingBottom();
        // On One UI phones SESL fades the list's top and bottom edges by erasing what the list
        // draws there, which uncovers the list's own background. SESL's preference list paints
        // that background in ?android:colorBackground (#17171A in dark mode), a lighter grey than
        // the page (#010102), and covers it with page-colored gaps between the cards. The fade
        // erased those gaps and showed the grey as a band behind the floating back button. The
        // list's background is the page color here, so the fade always uncovers the page itself.
        scroller.setBackgroundColor(pageColor());
        if (scroller instanceof NestedScrollView) {
            NestedScrollView scroll = (NestedScrollView) scroller;
            scroll.setClipToPadding(false);
            scroll.seslSetFadingEdgeEnabled(true);
            floating.setNestedScrollView(scroll);
        } else {
            RecyclerView list = (RecyclerView) scroller;
            list.setClipToPadding(false);
            list.seslSetFadingEdgeEnabled(true);
            floating.setRecyclerView(list);
        }
        applyBottomPadding();
        // The fading edge reads the status bar inset from the insets it receives.
        ViewCompat.requestApplyInsets(scroller);
    }

    /** The window background every One UI 9 page shows: SESL's round-and-background color. */
    private int pageColor() {
        return activity.getColor(R.color.oui_des_round_and_bgcolor);
    }

    private void applyBottomPadding() {
        View scroller = boundScroller;
        if (scroller == null) {
            return;
        }
        int bottom = scrollerBasePaddingBottom + bottomInset + bottomReserve;
        if (scroller.getPaddingBottom() != bottom) {
            scroller.setPadding(scroller.getPaddingLeft(), scroller.getPaddingTop(),
                    scroller.getPaddingRight(), bottom);
        }
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

    // ---- Ui.Header ----

    @Override
    public void setTitle(CharSequence title) {
        collapsing.setTitle(title);
        ActionBar actionBar = activity.getSupportActionBar();
        if (actionBar != null) {
            actionBar.setTitle(title);
        } else {
            toolbar.setTitle(title);
        }
    }

    @Override
    public CharSequence getTitle() {
        return collapsing.getTitle();
    }

    @Override
    public void setBack(boolean back) {
        ActionBar actionBar = activity.getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(back);
        }
        if (back) {
            toolbar.setNavigationOnClickListener(
                    v -> activity.getOnBackPressedDispatcher().onBackPressed());
        }
    }
}
