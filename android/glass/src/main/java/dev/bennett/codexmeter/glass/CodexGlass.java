package dev.bennett.codexmeter.glass;

import static com.benitbuhner.glasse.kit.GlassMenuKit.GlassMenu;
import static com.benitbuhner.glasse.kit.Kit.Anim;
import static com.benitbuhner.glasse.kit.Kit.Box;
import static com.benitbuhner.glasse.kit.Kit.Draw;
import static com.benitbuhner.glasse.kit.Kit.EdgeFade;
import static com.benitbuhner.glasse.kit.Kit.GlassButton;
import static com.benitbuhner.glasse.kit.Kit.GlassScrollView;
import static com.benitbuhner.glasse.kit.Kit.GlassSegmented;
import static com.benitbuhner.glasse.kit.Kit.GlassSwitch;
import static com.benitbuhner.glasse.kit.Kit.GlassTabBar;
import static com.benitbuhner.glasse.kit.Kit.GlassSlider;
import static com.benitbuhner.glasse.kit.Kit.Icon;
import static com.benitbuhner.glasse.kit.Kit.Img;
import static com.benitbuhner.glasse.kit.Kit.Row;
import static com.benitbuhner.glasse.kit.Kit.Stack;
import static com.benitbuhner.glasse.kit.Kit.Text;
import static com.benitbuhner.glasse.kit.KitMore.GlassSheet;
import static com.benitbuhner.glasse.kit.KitUi.BarChart;
import static com.benitbuhner.glasse.kit.KitControls.ActivityIndicator;
import static com.benitbuhner.glasse.kit.KitControls.ProgressBar;
import static com.benitbuhner.glasse.kit.KitControls.TextFieldRow;
import static com.benitbuhner.glasse.kit.KitPopup.GlassPopupMenu;
import static com.benitbuhner.glasse.kit.KitUi.GlassAlert;
import static com.benitbuhner.glasse.kit.KitUi.GlassNavBar;
import static com.benitbuhner.glasse.kit.KitUi.GlassSearchField;
import static com.benitbuhner.glasse.kit.KitUi.LargeTitle;
import static com.benitbuhner.glasse.kit.KitUi.ListPad;
import static com.benitbuhner.glasse.kit.KitUi.ListRow;
import static com.benitbuhner.glasse.kit.KitLists.ReorderList;
import static com.benitbuhner.glasse.kit.KitUi.ListSection;
import static com.benitbuhner.glasse.kit.KitUi.Page;

import com.benitbuhner.glasse.kit.AlertAction;
import com.benitbuhner.glasse.kit.Comp;
import com.benitbuhner.glasse.kit.El;
import com.benitbuhner.glasse.kit.KTheme;
import com.benitbuhner.glasse.kit.Kit;
import com.benitbuhner.glasse.kit.KitScreen;
import com.benitbuhner.glasse.kit.NodeDraw;
import com.benitbuhner.glasse.kit.KitLists;
import com.benitbuhner.glasse.kit.OnBool;
import com.benitbuhner.glasse.kit.OnInt;
import com.benitbuhner.glasse.kit.OnNum;
import com.benitbuhner.glasse.kit.OnScroll;
import com.benitbuhner.glasse.kit.OnStr;
import com.benitbuhner.glasse.kit.P;
import com.benitbuhner.glasse.kit.PopupItem;
import com.benitbuhner.glasse.kit.R;
import com.benitbuhner.glasse.kit.Ref;
import com.benitbuhner.glasse.kit.State;
import com.benitbuhner.glasse.port.Sk;
import com.benitbuhner.glasse.ui.Env;
import com.benitbuhner.glasse.ui.MenuItem;
import com.benitbuhner.glasse.ui.Screen;
import com.benitbuhner.glasse.ui.Tab;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import kotlin.Unit;

/**
 * Codex Meter's Liquid Glass screens, built from Glassé's kit the way Glassé's own demo app builds
 * its screens: the dashboard follows the demo's Music app (large title page, floating nav bar with
 * a trailing menu, the floating tab bar, edge fades), and Settings follows the demo's Settings app
 * (grouped list sections and rows, pushed sub pages, the bottom search field, glass alerts).
 */
public final class CodexGlass {
    private CodexGlass() {}

    public static final String ACCENT = "#007aff";
    public static final String DASHBOARD = "dashboard";
    public static final String SETTINGS = "settings";
    private static final String ROOT = "root";
    private static final String MORE = "more";
    private static final String CHOICE = "choice:";
    private static final Unit U = Unit.INSTANCE;
    /**
     * On a wide window (tablet, unfolded foldable) pages keep a readable column this wide, centred,
     * as Glassé's own pages and iPadOS do; on a phone the 16 pt gutter wins.
     */
    static final double READABLE_W = 720;

    static double readablePad(double width) {
        return Math.max(16, Math.round((width - READABLE_W) / 2));
    }

    public static Screen dashboard(Env env, GlassSource source) {
        GlassIcons.register();
        Comp app = p -> R.h(DashboardApp, p.with("src", source));
        return Kit.makeKitScreen(env, app, ACCENT);
    }

    public static Screen settings(Env env, GlassSource source) {
        GlassIcons.register();
        Comp app = p -> R.h(SettingsApp, p.with("src", source));
        return Kit.makeKitScreen(env, app, ACCENT);
    }

    /**
     * A screen that starts at page {@code start} (an activity of its own, such as widget
     * settings): the same pages as Settings; back on the first page calls
     * {@link GlassSource#closeSettings()}.
     */
    public static Screen page(Env env, GlassSource source, String start) {
        GlassIcons.register();
        Comp app = p -> R.h(SettingsApp, p.with("src", source, "start", start));
        return Kit.makeKitScreen(env, app, ACCENT);
    }

    /** Re-renders the calling component whenever the source says something changed. */
    private static int useSource(KitScreen kit, GlassSource src) {
        State<Integer> version = R.useState(0);
        R.useEffect(R.deps(src), () -> {
            Runnable off = src.subscribe(() -> {
                version.update(v -> v + 1);
                kit.scene.env.wake.invoke();
            });
            return () -> {
                off.run();
                return U;
            };
        });
        return version.get();
    }

    // =================================================================== dashboard

    private static final Comp DashboardApp = props -> {
        KitScreen kit = props.g("kit");
        GlassSource src = props.g("src");
        KTheme t = Kit.useTheme();
        int version = useSource(kit, src);
        State<Boolean> past = R.useState(false);
        State<Boolean> min = R.useState(false);
        State<Boolean> more = R.useState(false);
        OnScroll onScroll = R.useMemo(R.deps(), () -> (OnScroll) (y, s) -> {
            past.set(y > 46);
            min.set(s.minimized);
        });

        List<GlassModel.Tab> tabs = src.tabs();
        String selected = src.selectedTab();
        R.useEffectRun(R.deps(selected), () -> {
            past.set(false);
            min.set(false);
        });
        GlassModel.Dashboard d = src.dashboard();

        // Up to four providers get a tab each; with more, the first three and More (iOS's rule).
        boolean showsMore = tabs.size() > 4;
        int own = showsMore ? 3 : tabs.size();
        List<Tab> kitTabs = new ArrayList<>();
        StringBuilder tabKey = new StringBuilder("tabs");
        for (int i = 0; i < own; i++) {
            GlassModel.Tab tab = tabs.get(i);
            kitTabs.add(new Tab(tab.id, tab.label, tab.icon));
            tabKey.append('|').append(tab.id);
        }
        if (showsMore) {
            kitTabs.add(new Tab(MORE, "More", "more"));
            tabKey.append("|more");
        }
        int index = indexOf(tabs, selected);
        String value = more.get() || index >= own ? MORE : selected;

        // The bar's buttons (Edit dashboard, Settings): each its own glass button, side by side like
        // iOS's toolbar items. Pull the page down to refresh.
        List<El> trailing = new ArrayList<>();
        for (GlassSource.MenuEntry entry : src.menu()) {
            if (entry != null) {
                trailing.add(GlassButton(P.of("key", "b:" + entry.label, "id", "b:" + entry.icon,
                        "icon", entry.icon, "onPress", entry.onSelect)));
            }
        }

        List<Object> body = new ArrayList<>();
        body.add(LargeTitle(d.title, d.subtitle != null ? 2.0 : 18.0));
        if (d.subtitle != null) {
            body.add(Text(P.of("size", 15, "ls", -0.2, "lines", 1, "mb", 18, "color", t.pal.label2),
                    d.subtitle));
        }
        for (int i = 0; i < d.cards.size(); i++) {
            body.add(card(d.cards.get(i), t, "c" + i));
        }

        double[] bg = t.pal.groupedRGB;
        return Box(P.of("fill", true, "bg", t.pal.grouped),
                GlassScrollView(P.of("key", "page:" + selected, "padT", t.T + 64, "padX", readablePad(t.W), "tail", 150,
                        "onScroll", onScroll, "onRefresh", (Runnable) src::refresh,
                        "refreshing", src.refreshing()), body.toArray()),
                EdgeFade(P.of("edge", "top", "h", t.T + 92, "color", bg, "shown", past.get())),
                // Once content scrolls under the floating bars, the bar's own band gets an even
                // backdrop (a second fade over just the bar) instead of only the soft fade.
                EdgeFade(P.of("edge", "top", "h", t.T + 56, "color", bg, "shown", past.get())),
                EdgeFade(P.of("edge", "bottom", "h", t.B + 110, "color", bg)),
                GlassNavBar(P.of("title", d.title, "titleVisible", past.get(), "trailing", trailing)),
                kitTabs.size() >= 2 ? GlassTabBar(P.of(
                        "key", tabKey.toString(),
                        "tabs", kitTabs,
                        "value", value,
                        "onChange", (OnStr) id -> {
                            if (MORE.equals(id)) {
                                more.set(true);
                            } else {
                                src.selectTab(id);
                            }
                        },
                        "minimized", min.get(),
                        "onExpand", (Runnable) () -> min.set(false))) : null,
                showsMore ? GlassSheet(P.of("open", more.get(), "onClose", (Runnable) () -> more.set(false),
                        "title", "More", "detents", new double[] {sheetDetent(t, tabs.size() - own, 0, false)}),
                        moreList(tabs.subList(own, tabs.size()), selected, t, id -> {
                            src.selectTab(id);
                            more.set(false);
                        })) : null,
                R.h(CodexGlass.DialogLayer, P.of("src", src, "screen", DASHBOARD, "v", version)));
    };

    private static int indexOf(List<GlassModel.Tab> tabs, String id) {
        for (int i = 0; i < tabs.size(); i++) {
            if (tabs.get(i).id.equals(id)) {
                return i;
            }
        }
        return -1;
    }

    private static El moreList(List<GlassModel.Tab> rest, String selected, KTheme t, OnStr pick) {
        List<El> rows = new ArrayList<>();
        for (GlassModel.Tab tab : rest) {
            rows.add(ListRow(P.of("key", tab.id, "label", tab.label, "onPress", (Runnable) () -> pick.on(tab.id),
                    "leading", Box(P.of("w", 30, "h", 30, "align", "center", "justify", "center"),
                            Icon(P.of("name", tab.icon, "size", 22, "color", t.pal.label)))),
                    tab.id.equals(selected) ? Icon(P.of("name", "check", "size", 18, "color", t.accent)) : null));
        }
        return ListSection(P.of("mb", 0), rows);
    }

    /** One dashboard block. */
    private static El card(GlassModel.Card c, KTheme t, String key) {
        switch (c.kind) {
            case METER:
                return meter(c, t, key);
            case BALANCE:
                return ListSection(P.of("key", key, "header", c.title, "footer", c.detail),
                        ListRow(P.of("h", c.summary != null ? 66 : 52, "icon", c.icon, "color", tile(c.color),
                                "label", twoLine(c.value, c.summary, t))));
            case LINK:
                return ListSection(P.of("key", key, "footer", c.summary),
                        ListRow(P.of("icon", c.icon, "color", tile(c.color), "label", c.title, "value", c.value,
                                "chevron", c.onPress != null, "onPress", c.onPress)));
            case HISTORY:
                return ListSection(P.of("key", key, "footer", c.summary),
                        ListRow(P.of("icon", "chart", "color", tile(c.color), "label", c.title,
                                "value", c.value, "chevron", c.onPress != null, "onPress", c.onPress)),
                        ListPad(P.of("padY", 16),
                                BarChart(P.of("values", c.bars, "color", tile(c.color), "h", 88, "gap", 3.0,
                                        "radius", new double[] {4, 4, 2, 2})),
                                Row(P.of("mt", 8),
                                        Text(P.of("flex", 1, "size", 13, "lines", 1, "color", t.pal.label2),
                                                c.startLabel == null ? "" : c.startLabel),
                                        Text(P.of("size", 13, "lines", 1, "color", t.pal.label2),
                                                c.endLabel == null ? "" : c.endLabel))));
            case UPDATE:
                return ListSection(P.of("key", key),
                        ListPad(P.of("padY", 18),
                                Text(P.of("size", 17, "weight", 600, "ls", -0.34, "lh", 22, "lines", 2,
                                        "color", t.pal.label), c.title),
                                c.summary != null ? Text(P.of("size", 15, "ls", -0.15, "lh", 20, "lines", 8,
                                        "mt", 4, "color", c.warning ? red(t) : t.pal.label2), c.summary) : null,
                                c.detail != null ? Text(P.of("size", 13, "lh", 17.55, "lines", 8, "mt", 8,
                                        "color", t.pal.label2), c.detail) : null,
                                c.button != null ? Box(P.of("mt", 16, "opacity", c.buttonEnabled ? 1.0 : 0.4,
                                                "pointerEvents", c.buttonEnabled ? null : "none"),
                                        GlassButton(P.of("variant", "filled", "label", c.button, "h", 50, "r", 25,
                                                "fillColor", t.accent, "color", "#fff", "onPress", c.onPress)))
                                        : null,
                                c.progress >= 0 ? bar(Math.min(1, c.progress), t.accent, t.pal.fill, 14) : null,
                                c.value != null ? Text(P.of("size", 13, "lh", 17.55, "lines", 4, "mt", 10,
                                        "color", c.fast ? red(t) : t.pal.label2), c.value) : null));
            case CHART:
                return GlassChart.card(c, key);
            case STATS: {
                // label and value rows, like a Settings detail list
                List<El> rows = new ArrayList<>();
                for (int i = 0; i < c.stats.size(); i++) {
                    String[] stat = c.stats.get(i);
                    rows.add(ListRow(P.of("key", "s" + i, "h", stat[0].length() + stat[1].length() > 36 ? 66 : 52,
                            "label", Text(P.of("size", 17, "ls", -0.34, "lines", 2, "color", t.pal.label), stat[0])),
                            Text(P.of("size", 17, "ls", -0.34, "lines", 1, "color", t.pal.label2), stat[1])));
                }
                if (c.onPress != null) {
                    // a closing row that acts on the card ("Change model")
                    rows.add(ListRow(P.of("key", "press", "label", c.value != null ? c.value : "More",
                            "chevron", true, "onPress", c.onPress)));
                }
                String header = c.title != null ? c.title : c.detail;
                return ListSection(P.of("key", key, "header", header, "footer", c.summary), rows);
            }
            case NOTE:
                return ListSection(P.of("key", key, "header", c.detail),
                        ListPad(P.of("padY", 14),
                                c.title != null ? Text(P.of("size", 17, "weight", 600, "lines", 2, "mb", 6,
                                        "color", t.pal.label), c.title) : null,
                                Text(P.of("size", 15, "lh", 20, "lines", 40, "color", t.pal.label2),
                                        c.summary == null ? "" : c.summary)));
            case HEADING:
                // like a grouped list's section header, for a run of cards that have none of their own
                return Text(P.of("key", key, "size", 13, "ls", -0.08, "lines", 1, "ml", 16, "mr", 16,
                        "mt", 6, "mb", 7, "color", t.pal.label2), c.title.toUpperCase(java.util.Locale.ROOT));
            case SIGN_IN:
                return Box(P.of("key", key, "align", "center", "padT", 24, "padX", 8),
                        Box(P.of("w", 72, "h", 72, "r", 18, "bg", tile(c.color), "align", "center",
                                "justify", "center", "mb", 18),
                                Icon(P.of("name", c.icon, "size", 44, "color", "#fff"))),
                        Text(P.of("size", 22, "weight", 700, "ls", -0.55, "lh", 28, "lines", 2,
                                "align", "center", "color", t.pal.label), c.title),
                        Text(P.of("size", 15, "ls", -0.15, "lh", 20.25, "lines", 8, "mt", 8,
                                "align", "center", "color", t.pal.label2), c.summary),
                        GlassButton(P.of("mt", 26, "alignSelf", "stretch", "h", 50, "r", 25,
                                "variant", "filled", "label", c.button, "fillColor", t.accent,
                                "color", "#fff", "onPress", c.onPress)));
            case ERROR:
                return Text(P.of("key", key, "size", 15, "lh", 20, "lines", 6, "ml", 16, "mr", 16, "mb", 18,
                        "color", "dark".equals(t.scheme) ? "#ff453a" : "#ff3b30"), c.title);
            case MESSAGE:
            default:
                return Text(P.of("key", key, "size", 15, "lh", 20, "lines", 6, "align", "center",
                        "mt", 24, "ml", 24, "mr", 24, "mb", 18, "color", t.pal.label2), c.title);
        }
    }

    /**
     * A usage window: its name and reset time, what's left in large type with a ring gauge, a
     * capsule bar, and the pace as a row of its own (orange when it runs out early).
     */
    private static El meter(GlassModel.Card c, KTheme t, String key) {
        boolean dark = "dark".equals(t.scheme);
        String fill = c.remaining <= 0.1 ? (dark ? "#ff453a" : "#ff3b30")
                : c.remaining <= 0.25 ? (dark ? "#ff9f0a" : "#ff9500")
                : (dark ? "#30d158" : "#34c759");
        String warn = dark ? "#ff9f0a" : "#c93400";
        double v = Math.max(0, Math.min(1, c.remaining));
        boolean pace = c.pace != null && !c.pace.isEmpty();
        return ListSection(P.of("key", key),
                ListPad(P.of("padY", 16),
                        Row(P.of("gap", 10),
                                Box(P.of("w", 30, "h", 30, "r", 8, "bg", tile(c.color), "align", "center",
                                        "justify", "center"),
                                        Icon(P.of("name", c.icon, "size", 19, "color", "#fff"))),
                                Text(P.of("flex", 1, "size", 17, "weight", 600, "ls", -0.34, "lines", 1,
                                        "color", t.pal.label), c.title),
                                c.detail != null ? Text(P.of("size", 15, "ls", -0.2, "lines", 1,
                                        "color", t.pal.label2), c.detail) : null),
                        Row(P.of("mt", 12, "align", "end", "gap", 6),
                                Text(P.of("size", 34, "weight", 700, "ls", -1.19, "lh", 38.08, "tabular", true,
                                        "color", t.pal.label), c.value),
                                Text(P.of("flex", 1, "size", 17, "ls", -0.34, "mb", 5, "color", t.pal.label2),
                                        "left")),
                        ProgressBar(P.of("mt", 12, "h", 8, "value", v, "color", fill)),
                        pace ? Row(P.of("mt", 14, "gap", 8, "align", "center"),
                                Icon(P.of("name", c.fast ? "bolt" : "waveform", "size", 15,
                                        "color", c.fast ? warn : t.pal.label2)),
                                Text(P.of("flex", 1, "size", 15, "ls", -0.2, "lines", 1,
                                        "color", c.fast ? warn : t.pal.label2), c.paceLabel),
                                Text(P.of("size", 15, "weight", 600, "ls", -0.2, "lines", 1, "tabular", true,
                                        "color", c.fast ? warn : t.pal.label), c.pace)) : null,
                        !pace && c.summary != null ? Text(P.of("size", 13, "lh", 17.55, "lines", 3, "mt", 10,
                                "color", c.fast ? warn : t.pal.label2), c.summary) : null));
    }

    private static String tile(String color) {
        return color != null ? color : ACCENT;
    }

    private static String red(KTheme t) {
        return "dark".equals(t.scheme) ? "#ff453a" : "#ff3b30";
    }

    /** A capsule progress bar. */
    private static El bar(double v, String fill, String track, double mt) {
        return Draw(P.of("h", 8, "mt", mt, "v", v, "fill", fill, "track", track,
                "draw", (NodeDraw) (cv, w, h, n, ax, ay) -> {
                    double r = h / 2;
                    cv.drawRRect(Sk.rrect(0, 0, w, h, r), Sk.paint(Sk.color(n.props.s("track"))));
                    double fw = Math.max(h, w * n.props.d("v", 0.0));
                    if (n.props.d("v", 0.0) > 0) {
                        cv.drawRRect(Sk.rrect(0, 0, fw, h, r), Sk.paint(Sk.color(n.props.s("fill"))));
                    }
                }));
    }

    /** A row title with a capsule tag after it ("Experimental"), and its summary under. */
    private static Object tagged(GlassModel.Row r, KTheme t) {
        El title = Row(P.of("gap", 8, "align", "center"),
                Text(P.of("size", 17, "ls", -0.34, "lh", 22, "lines", 1, "color", t.pal.label), r.title),
                Box(P.of("h", 20, "r", 10, "padX", 8, "justify", "center", "bg", t.pal.fill),
                        Text(P.of("size", 12, "weight", 600, "lines", 1, "color", t.pal.label2), r.tag)));
        if (r.summary == null || r.summary.isEmpty()) {
            return Box(P.of("flex", 1), title);
        }
        return Box(P.of("flex", 1, "gap", 2), title,
                Text(P.of("size", 13, "lh", 16.5, "lines", 3, "color", t.pal.label2), r.summary));
    }

    /** A row label with a smaller second line under it. */
    private static Object twoLine(String title, String summary, KTheme t) {
        if (summary == null || summary.isEmpty()) {
            return title == null ? "" : title;
        }
        return Box(P.of("flex", 1, "gap", 2),
                Text(P.of("size", 17, "ls", -0.34, "lh", 22, "lines", 1, "color", t.pal.label), title),
                Text(P.of("size", 13, "lh", 16.5, "lines", 3, "color", t.pal.label2), summary));
    }

    // =================================================================== settings

    private static final Comp SettingsApp = props -> {
        KitScreen kit = props.g("kit");
        GlassSource src = props.g("src");
        KTheme t = Kit.useTheme();
        int version = useSource(kit, src);
        String start = props.s("start");
        State<List<String>> stack = R.useState(Collections.singletonList(start != null ? start : ROOT));
        State<Boolean> past = R.useState(false);
        State<String> query = R.useState("");
        // the page whose reorderable lists are in edit mode (≡ handles), "" for none
        State<String> editing = R.useState("");
        OnScroll onScroll = R.useMemo(R.deps(), () -> (OnScroll) (y, s) -> past.set(y > 46));
        OnStr push = R.useMemo(R.deps(), () -> (OnStr) id -> stack.update(s -> {
            List<String> n = new ArrayList<>(s);
            n.add(id);
            return n;
        }));
        Runnable pop = R.useMemo(R.deps(), () -> (Runnable) () -> stack.update(s ->
                s.size() <= 1 ? s : new ArrayList<>(s.subList(0, s.size() - 1))));
        List<String> pages = stack.get();
        String top = pages.get(pages.size() - 1);
        R.useEffectRun(R.deps(top), () -> {
            past.set(false);
            src.shown(top);
        });
        R.useEffectRun(R.deps(version), () -> {
            GlassSource.Nav nav = src.takeNav();
            if (nav == null) {
                return;
            }
            if (nav.reset) {
                query.set("");
            }
            if (nav.pop) {
                if (!kit.back()) {
                    src.closeSettings();
                }
                return;
            }
            stack.update(s -> {
                if (nav.reset && nav.alone && nav.page != null) {
                    return Collections.singletonList(nav.page);
                }
                List<String> n = new ArrayList<>(nav.reset ? Collections.singletonList(ROOT) : s);
                if (nav.page != null && !ROOT.equals(nav.page) && !nav.page.equals(n.get(n.size() - 1))) {
                    n.add(nav.page);
                }
                return n;
            });
        });
        Env env = kit.scene.env;
        boolean sub = pages.size() > 1 || !ROOT.equals(top);
        GlassModel.Page topPage = top.startsWith(CHOICE) ? null : src.page(top);
        List<El> actions = new ArrayList<>();
        if (topPage != null && reorderable(topPage)) {
            // iOS: Edit shows the reorder handles, Done (filled) puts them away
            boolean on = top.equals(editing.get());
            actions.add(GlassButton(P.of("key", "edit", "id", "edit", "label", on ? "Done" : "Edit", "padX", 16,
                    "variant", on ? "filled" : "glass", "fillColor", on ? t.accent : null, "color", on ? "#fff" : null,
                    "onPress", (Runnable) () -> editing.set(on ? "" : top))));
        }
        if (topPage != null) {
            for (GlassModel.Action a : topPage.actions) {
                actions.add(a.icon != null
                        ? GlassButton(P.of("key", "a:" + a.label, "icon", a.icon, "onPress", a.onPress))
                        : GlassButton(P.of("key", "a:" + a.label, "label", a.label, "padX", 16,
                                "variant", a.prominent ? "filled" : null, "fillColor", a.prominent ? t.accent : null,
                                "color", a.prominent ? "#fff" : null, "onPress", a.onPress)));
            }
        }

        List<El> els = new ArrayList<>();
        for (String id : pages) {
            els.add(Box(P.of("key", id, "fill", true),
                    R.h(CodexGlass.SettingsPage, P.of("id", id, "src", src, "v", version, "onScroll", onScroll,
                            "editing", id.equals(editing.get()),
                            "push", push, "query", ROOT.equals(id) ? query.get() : ""))));
        }
        double[] bg = t.pal.groupedRGB;
        return Box(P.of("fill", true, "bg", t.pal.grouped),
                Stack(P.of("onPop", pop, "bg", t.pal.grouped), els),
                EdgeFade(P.of("edge", "top", "h", t.T + 92, "color", bg, "shown", past.get())),
                // Once content scrolls under the floating bars, the bar's own band gets an even
                // backdrop (a second fade over just the bar) instead of only the soft fade.
                EdgeFade(P.of("edge", "top", "h", t.T + 56, "color", bg, "shown", past.get())),
                EdgeFade(P.of("edge", "bottom", "h", t.B + 110, "color", bg)),
                GlassNavBar(P.of("title", titleOf(src, top), "titleVisible", sub || past.get(),
                        "trailing", actions.isEmpty() ? null : actions,
                        "onBack", (Runnable) () -> {
                            if (!kit.back()) {
                                if (start != null) {
                                    src.closeSettings();
                                } else {
                                    env.getBack().invoke();
                                }
                            }
                        })),
                ROOT.equals(pages.get(0)) ? Anim(P.of("abs", true, "left", 16, "right", 16, "bottom", t.B + 14,
                                "to", P.of("opacity", sub ? 0 : 1, "scale", sub ? 0.96 : 1),
                                "pointerEvents", sub ? "none" : null),
                        GlassSearchField(P.of("placeholder", "Search", "value", query.get(),
                                "onChange", (OnStr) query::set))) : null,
                R.h(CodexGlass.DialogLayer, P.of("src", src, "screen", SETTINGS, "v", version)));
    };

    private static String titleOf(GlassSource src, String id) {
        if (id.startsWith(CHOICE)) {
            GlassModel.Row row = choiceRow(src, id);
            return row == null ? "" : row.title;
        }
        GlassModel.Page page = src.page(id);
        return page == null ? "" : page.title;
    }

    /** The CHOICE row a "choice:page:key" page picks for. */
    private static GlassModel.Row choiceRow(GlassSource src, String id) {
        String rest = id.substring(CHOICE.length());
        int split = rest.indexOf(':');
        if (split < 0) {
            return null;
        }
        return find(src.page(rest.substring(0, split)), rest.substring(split + 1));
    }

    private static GlassModel.Row find(GlassModel.Page page, String key) {
        if (page == null) {
            return null;
        }
        for (GlassModel.Section section : page.sections) {
            for (GlassModel.Row row : section.rows) {
                if (row.key != null && row.key.equals(key)) {
                    return row;
                }
            }
        }
        return null;
    }

    private static final Comp SettingsPage = p -> {
        KTheme t = Kit.useTheme();
        GlassSource src = p.g("src");
        String id = p.s("id");
        OnStr push = p.g("push");
        boolean root = ROOT.equals(id);
        List<Object> body = new ArrayList<>();
        if (id.startsWith(CHOICE)) {
            String rest = id.substring(CHOICE.length());
            String pageId = rest.substring(0, Math.max(0, rest.indexOf(':')));
            GlassModel.Row row = choiceRow(src, id);
            body.add(LargeTitle(row == null ? "" : row.title, 18.0));
            if (row != null && row.entries != null) {
                List<El> rows = new ArrayList<>();
                for (int i = 0; i < row.entries.length; i++) {
                    int which = i;
                    rows.add(ListRow(P.of("key", "e" + i, "label", row.entries[i],
                                    "onPress", (Runnable) () -> {
                                        if (row.onChoose != null) {
                                            row.onChoose.on(which);
                                        } else {
                                            src.choose(pageId, row.key, which);
                                        }
                                    }),
                            i == row.selected ? Icon(P.of("name", "check", "size", 18, "color", t.accent)) : null));
                }
                body.add(ListSection(P.of("footer", row.summary), rows));
            }
            return Page(P.of("onScroll", p.get("onScroll"), "tail", 60, "maxW", READABLE_W), body);
        }
        GlassModel.Page page = src.page(id);
        if (page == null) {
            return Page(P.of("onScroll", p.get("onScroll"), "maxW", READABLE_W));
        }
        body.add(LargeTitle(page.title, page.intro != null ? 6.0 : 18.0));
        if (page.intro != null) {
            body.add(Text(P.of("size", 15, "lh", 20, "lines", 12, "ml", 4, "mr", 4, "mb", 22,
                    "color", t.pal.label2), page.intro));
        }
        String query = p.s("query");
        if (root && query != null && !query.trim().isEmpty()) {
            body.add(searchResults(src, query.trim(), push, t));
        } else {
            for (int s = 0; s < page.sections.size(); s++) {
                El section = section(page.sections.get(s), id, src, push, t, "s" + s, p.b("editing"));
                if (section != null) {
                    body.add(section);
                }
            }
        }
        return Page(P.of("onScroll", p.get("onScroll"), "tail", root ? 110 : 60, "maxW", READABLE_W), body);
    };

    private static boolean reorderable(GlassModel.Page page) {
        for (GlassModel.Section section : page.sections) {
            if (section.onMove != null && section.rows.size() > 1) {
                return true;
            }
        }
        return false;
    }

    private static El section(GlassModel.Section section, String pageId, GlassSource src, OnStr push,
            KTheme t, String key, boolean editing) {
        if (!section.cards.isEmpty()) {
            List<El> cards = new ArrayList<>();
            for (int i = 0; i < section.cards.size(); i++) {
                cards.add(card(section.cards.get(i), t, key + "c" + i));
            }
            return Box(P.of("key", key), cards);
        }
        if (section.onMove != null && !section.rows.isEmpty()) {
            // Glassé's ReorderList: Edit shows ≡ handles, or touch and hold a row to drag it
            List<GlassModel.Row> list = section.rows;
            List<String> items = new ArrayList<>();
            Map<String, GlassModel.Row> byKey = new HashMap<>();
            double rowH = 52;
            for (int i = 0; i < list.size(); i++) {
                GlassModel.Row r = list.get(i);
                String k = r.key != null && !byKey.containsKey(r.key) ? r.key : "row" + i;
                items.add(k);
                byKey.put(k, r);
                rowH = Math.max(rowH, rowHeight(r));
            }
            GlassModel.Mover mover = section.onMove;
            return ReorderList(P.of("key", key, "header", section.header, "footer", section.footer,
                    "items", items, "editing", editing, "rowH", rowH, "inset", 58,
                    "onMove", (KitLists.OnMove) mover::move,
                    "renderRow", (KitLists.RenderRow) (k, i) -> reorderRow(byKey.get(k), pageId, src, t)));
        }
        List<El> rows = new ArrayList<>();
        for (GlassModel.Row row : section.rows) {
            rows.add(row(row, pageId, src, push, t, null));
        }
        if (rows.isEmpty() && section.footer == null) {
            return null;
        }
        if (rows.isEmpty()) {
            return Text(P.of("key", key, "size", 13, "lh", 17.55, "lines", 8, "ml", 16, "mr", 16, "mb", 22,
                    "color", t.pal.label2), section.footer);
        }
        return ListSection(P.of("key", key, "header", section.header, "footer", section.footer), rows);
    }

    /** A row's content inside a ReorderList (which supplies the padding, handle and separators). */
    private static El reorderRow(GlassModel.Row r, String pageId, GlassSource src, KTheme t) {
        if (r == null) {
            return null;
        }
        String icon = r.plainIcon || r.image != null ? null : r.icon;
        Object leading = icon != null
                ? Box(P.of("w", 30, "h", 30, "r", 30 * 0.225, "bg", r.color, "align", "center", "justify", "center"),
                        Icon(P.of("name", icon, "size", 20, "color", "#fff")))
                : leading(r, t);
        Object label = r.tag != null ? tagged(r, t) : twoLine(r.title, r.summary, t);
        if (label instanceof String) {
            label = Text(P.of("size", 17, "ls", -0.34, "lines", 1, "color", t.pal.label), label);
        }
        El trailing = null;
        if (r.kind == GlassModel.Row.Kind.SWITCH || r.kind == GlassModel.Row.Kind.SWITCH_LINK) {
            trailing = GlassSwitch(P.of("value", r.checked, "onChange", (OnBool) v -> {
                if (r.onToggle != null) {
                    r.onToggle.on(v);
                } else {
                    src.toggle(pageId, r.key, v);
                }
            }));
        } else if (r.value != null) {
            trailing = Text(P.of("size", 17, "ls", -0.34, "lines", 1, "color", t.pal.label2), r.value);
        }
        return Row(P.of("flex", 1, "gap", 12, "align", "center"),
                leading, Box(P.of("flex", 1), label), trailing);
    }

    /** A CHOICE row whose value opens an anchored glass pop-up menu (Picker .menu in a Form). */
    private static final Comp ChoiceRow = p -> {
        KTheme t = Kit.useTheme();
        GlassModel.Row r = p.g("r");
        OnInt choose = p.g("choose");
        State<Boolean> open = R.useState(false);
        List<PopupItem> items = new ArrayList<>();
        for (int i = 0; i < r.entries.length; i++) {
            int index = i;
            items.add(new PopupItem(r.entries[i]).checked(i == r.selected).onSelect(() -> choose.on(index)));
        }
        String value = r.selected >= 0 && r.selected < r.entries.length ? shortValue(r.entries[r.selected]) : null;
        return R.frag(
                ListRow(P.of("h", p.d("h", 52.0), "icon", p.s("icon"), "color", r.color, "label", p.get("label"),
                                "leading", p.get("leading"),
                                "onPress", r.enabled ? (Runnable) () -> open.set(true) : null),
                        Row(P.of("gap", 5, "align", "center", "ml", 8),
                                value != null ? Text(P.of("size", 17, "ls", -0.34, "lines", 1,
                                        "color", t.pal.label2), value) : null,
                                Icon(P.of("name", "chevronUpDown", "size", 15, "color", t.pal.label2)))),
                GlassPopupMenu(P.of("open", open.get(), "onClose", (Runnable) () -> open.set(false),
                        "items", items, "width", 280)));
    };

    private static El row(GlassModel.Row r, String pageId, GlassSource src, OnStr push, KTheme t) {
        return row(r, pageId, src, push, t, null);
    }

    private static El row(GlassModel.Row r, String pageId, GlassSource src, OnStr push, KTheme t, El extra) {
        String key = r.key != null ? r.key : r.title;
        double h = rowHeight(r);
        Object label = r.tag != null ? tagged(r, t) : twoLine(r.title, r.summary, t);
        OnBool toggle = v -> {
            if (r.onToggle != null) {
                r.onToggle.on(v);
            } else {
                src.toggle(pageId, r.key, v);
            }
        };
        OnInt choose = i -> {
            if (r.onChoose != null) {
                r.onChoose.on(i);
            } else {
                src.choose(pageId, r.key, i);
            }
        };
        Runnable press = () -> {
            if (r.onPress != null) {
                r.onPress.run();
            } else {
                src.press(pageId, r.key);
            }
        };
        // Icons: a colored tile (iOS Settings), a plain mark in the text color, or a picture.
        String icon = r.plainIcon || r.image != null ? null : r.icon;
        Object leading = leading(r, t);
        El el;
        switch (r.kind) {
            case SWITCH:
                el = ListRow(P.of("key", key, "h", h, "icon", icon, "color", r.color, "label", label,
                                "leading", leading),
                        GlassSwitch(P.of("value", r.checked, "onChange", toggle)), extra);
                break;
            case SWITCH_LINK:
                el = ListRow(P.of("key", key, "h", h, "icon", icon, "color", r.color, "label", label,
                                "leading", leading, "onPress", open(r, pageId, src, push)),
                        GlassSwitch(P.of("value", r.checked, "onChange", toggle)),
                        Icon(P.of("name", "chevronRight", "size", 15, "color", t.pal.label3)));
                break;
            case BUTTON:
                // inset from the card's rounded edges like the other rows
                el = ListPad(P.of("key", key, "padY", 12, "padX", 16),
                        GlassButton(P.of("variant", "filled", "label", r.title, "h", 50, "r", 25,
                                "fillColor", r.destructive ? null : t.accent,
                                "color", r.destructive ? red(t) : "#fff", "onPress", press)));
                break;
            case SLIDER:
                el = ListPad(P.of("key", key),
                        r.title.isEmpty() ? null : Row(P.of("mb", 6),
                                Text(P.of("flex", 1, "size", 17, "ls", -0.34, "lines", 1, "color", t.pal.label),
                                        r.title),
                                r.value != null ? Text(P.of("size", 17, "lines", 1, "color", t.pal.label2),
                                        r.value) : null),
                        GlassSlider(P.of("value", r.fraction, "min", 0.0, "max", 1.0,
                                "step", r.steps > 1 ? 1.0 / (r.steps - 1) : 0.0, "ticks", r.steps > 1,
                                "onChange", (OnNum) v -> {
                                    if (r.onSlide != null) {
                                        r.onSlide.on(v);
                                    }
                                })));
                break;
            case IMAGE:
                el = ListPad(P.of("key", key, "padY", 16),
                        Img(P.of("src", GlassImages.of(r.image), "h", r.imageHeight, "fit", "contain")));
                break;
            case FIELD: {
                OnStr typed = text -> {
                    if (r.onText != null) {
                        r.onText.on(text);
                    }
                };
                el = Row(P.of("key", key, "align", "center", "padR", r.onPress != null ? 12 : 0),
                        Box(P.of("flex", 1),
                                TextFieldRow(P.of("label", r.title.isEmpty() ? null : r.title,
                                        "value", r.value == null ? "" : r.value, "placeholder", r.hint,
                                        "secure", r.secret, "onChange", typed))),
                        r.onPress != null ? GlassButton(P.of("label", "Paste", "h", 34, "padX", 14,
                                "onPress", r.onPress)) : null);
                break;
            }
            case CODE:
                el = ListPad(P.of("key", key, "padY", 18, "align", "center"),
                        r.title.isEmpty() ? null : Text(P.of("size", 13, "lines", 1, "color", t.pal.label2,
                                "align", "center"), r.title),
                        Text(P.of("size", 34, "weight", 700, "ls", 3, "lines", 1, "mt", 6, "tabular", true,
                                "align", "center", "color", t.pal.label), r.value == null ? "" : r.value),
                        r.summary == null ? null : Row(P.of("mt", 12, "gap", 8, "align", "center"),
                                ActivityIndicator(P.of()),
                                Text(P.of("size", 15, "lines", 2, "color", t.pal.label2), r.summary)));
                break;
            case TEXT:
                el = ListPad(P.of("key", key, "padY", 12),
                        r.title.isEmpty() ? null : Text(P.of("size", 17, "weight", 600, "lines", 2, "mb", 4,
                                "color", t.pal.label), r.title),
                        Text(P.of("size", 15, "lh", 20, "lines", 60, "color", t.pal.label2),
                                r.summary == null ? "" : r.summary));
                break;
            case CHOICE:
                if (r.entries != null && r.entries.length > 0 && r.entries.length <= 12) {
                    // iOS 26's pop-up menu: the choices grow out of the row's value, the current one checked
                    el = R.h(ChoiceRow, P.of("key", key, "r", r, "h", h, "icon", icon, "label", label,
                            "leading", leading, "choose", choose));
                    break;
                }
                el = ListRow(P.of("key", key, "h", h, "icon", icon, "color", r.color, "label", label,
                        "leading", leading,
                        "value", r.selected >= 0 && r.entries != null && r.selected < r.entries.length
                                ? shortValue(r.entries[r.selected]) : null,
                        "chevron", true,
                        "onPress", (Runnable) () -> push.on(CHOICE + pageId + ":" + r.key)));
                break;
            case SEGMENTED:
                List<String> labels = new ArrayList<>();
                if (r.entries != null) {
                    Collections.addAll(labels, r.entries);
                }
                el = ListPad(P.of("key", key),
                        GlassSegmented(P.of("labels", labels, "index", Math.max(0, r.selected),
                                "onChange", choose)));
                break;
            case RADIO:
                el = ListRow(P.of("key", key, "h", h, "icon", icon, "color", r.color, "leading", leading,
                                "label", label, "onPress", press),
                        r.checked ? Icon(P.of("name", "check", "size", 18, "color", t.accent)) : null);
                break;
            case ACTION:
                el = ListRow(P.of("key", key, "h", h, "icon", icon, "color", r.color, "leading", leading,
                        "label", actionLabel(r, t), "onPress", press));
                break;
            case INFO:
                el = ListRow(P.of("key", key, "h", h, "icon", icon, "color", r.color, "label", label,
                        "leading", leading, "value", r.value), extra);
                break;
            case LINK:
            default:
                el = ListRow(P.of("key", key, "h", h, "icon", icon, "color", r.color, "label", label,
                        "leading", leading, "value", r.value, "chevron", true,
                        "onPress", r.onPress != null ? r.onPress : open(r, pageId, src, push)));
                break;
        }
        if (!r.enabled) {
            return Box(P.of("key", key, "icon", r.icon, "opacity", 0.38, "pointerEvents", "none"), el);
        }
        return el;
    }

    /** The start of a row: a picture, or a plain mark in the text color; null for a tile or nothing. */
    private static Object leading(GlassModel.Row r, KTheme t) {
        if (r.image != null && r.kind != GlassModel.Row.Kind.IMAGE) {
            double s = r.imageSize;
            return Img(P.of("src", GlassImages.of(r.image), "w", s, "h", s, "r", r.roundImage ? s / 2 : s * 0.225));
        }
        if (r.plainIcon && r.icon != null) {
            return Box(P.of("w", 30, "h", 30, "align", "center", "justify", "center"),
                    Icon(P.of("name", r.icon, "size", 22, "color", t.pal.label)));
        }
        return null;
    }

    private static Runnable open(GlassModel.Row r, String pageId, GlassSource src, OnStr push) {
        return () -> {
            if (r.link != null) {
                push.on(r.link);
            } else {
                src.press(pageId, r.key);
            }
        };
    }

    /** Long values ("Every 15 minutes (recommended)") keep their first part in the row. */
    private static String shortValue(String value) {
        int paren = value.indexOf(" (");
        return paren > 0 ? value.substring(0, paren) : value;
    }

    private static Object actionLabel(GlassModel.Row r, KTheme t) {
        // iOS Settings: an action row reads in the label color (Shut Down, Reset); only destructive ones are red
        String color = r.destructive ? ("dark".equals(t.scheme) ? "#ff453a" : "#ff3b30") : t.pal.label;
        El title = Text(P.of("size", 17, "ls", -0.34, "lh", 22, "lines", 1, "color", color), r.title);
        if (r.summary == null || r.summary.isEmpty()) {
            return Box(P.of("flex", 1), title);
        }
        return Box(P.of("flex", 1, "gap", 2), title,
                Text(P.of("size", 13, "lh", 16.5, "lines", 3, "color", t.pal.label2), r.summary));
    }

    /** One line: 52pt like iOS; a summary adds a line or two of 13pt text. */
    private static double rowHeight(GlassModel.Row r) {
        double min = r.image != null && r.imageSize > 36 ? r.imageSize + 24 : 0;
        if (r.summary == null || r.summary.isEmpty()) {
            return Math.max(52, min);
        }
        int lines = Math.min(3, 1 + r.summary.length() / 44);
        return Math.max(min, 44 + 22 + lines * 16.5 - 8);
    }

    private static El searchResults(GlassSource src, String query, OnStr push, KTheme t) {
        String q = query.toLowerCase(Locale.ROOT);
        List<El> hits = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        collect(src, ROOT, q, push, t, hits, seen, 0);
        if (hits.isEmpty()) {
            return Text(P.of("size", 17, "align", "center", "mt", 30, "color", t.pal.label2),
                    "No Results for “" + query + "”");
        }
        return ListSection(P.of("header", "Results"), hits);
    }

    private static void collect(GlassSource src, String pageId, String q, OnStr push, KTheme t,
            List<El> hits, List<String> seen, int depth) {
        if (depth > 2 || seen.contains(pageId)) {
            return;
        }
        seen.add(pageId);
        GlassModel.Page page = src.page(pageId);
        if (page == null) {
            return;
        }
        for (GlassModel.Section section : page.sections) {
            for (GlassModel.Row row : section.rows) {
                boolean match = row.title.toLowerCase(Locale.ROOT).contains(q)
                        || (row.summary != null && row.summary.toLowerCase(Locale.ROOT).contains(q));
                if (match) {
                    String target = row.link != null ? row.link : pageId;
                    String where = ROOT.equals(pageId) ? null : page.title;
                    hits.add(ListRow(P.of("key", "hit:" + pageId + ":" + row.key + ":" + hits.size(),
                            "h", where != null ? 62 : 52, "label", twoLine(row.title, where, t), "chevron", true,
                            "onPress", (Runnable) () -> {
                                if (ROOT.equals(target)) {
                                    src.press(pageId, row.key);
                                } else {
                                    push.on(target);
                                }
                            })));
                }
                if (row.link != null) {
                    collect(src, row.link, q, push, t, hits, seen, depth + 1);
                }
            }
        }
    }

    // =================================================================== alerts and sheets

    private static final Comp DialogLayer = p -> {
        KTheme t = Kit.useTheme();
        GlassSource src = p.g("src");
        GlassModel.Dialog d = src.dialog(p.s("screen"));
        // The last dialog stays on screen while it animates away.
        Ref<GlassModel.Dialog> last = R.useRef(null);
        if (d != null) {
            last.current = d;
        }
        GlassModel.Dialog shown = last.current;
        Runnable close = () -> {
            if (shown != null && shown.onClose != null) {
                shown.onClose.run();
            }
        };
        boolean alertOpen = d != null && d.items == null && d.amount == null;
        boolean sheetOpen = d != null && (d.items != null || d.amount != null);
        List<AlertAction> actions = new ArrayList<>();
        if (shown != null) {
            for (GlassModel.Dialog.Button b : shown.buttons) {
                actions.add(new AlertAction(b.label, b.style, b.onPress));
            }
        }
        if (actions.isEmpty()) {
            actions.add(new AlertAction("OK", GlassModel.Dialog.PROMINENT, null));
        }
        boolean isSheet = shown != null && (shown.items != null || shown.amount != null);
        return Box(P.of("abs", true, "left", 0, "top", 0, "w", t.W, "h", t.H, "pointerEvents", null),
                !isSheet ? GlassAlert(P.of("key", "alert", "open", alertOpen, "onClose", close,
                        "title", shown == null ? "" : shown.title,
                        "message", shown == null ? null : shown.message,
                        "actions", actions)) : null,
                isSheet ? GlassSheet(P.of("key", "sheet", "open", sheetOpen, "onClose", close,
                                "title", shown.title,
                                "detents", new double[] {sheetDetent(t, shown.items != null ? shown.items.length : 3,
                                        shown.message == null ? 0 : shown.message.length(),
                                        !shown.buttons.isEmpty())}),
                        sheetBody(shown, t)) : null);
    };

    private static double sheetDetent(KTheme t, int rows, int messageChars, boolean buttons) {
        double h = 52 + 24 + rows * 52.5 + 8;
        if (messageChars > 0) {
            h += 20 * (1 + messageChars / 46) + 14;
        }
        if (buttons) {
            h += 48 + 16;
        }
        h += t.B;
        return Math.max(0.3, Math.min(0.92, h / t.H));
    }

    private static El sheetBody(GlassModel.Dialog d, KTheme t) {
        List<El> rows = new ArrayList<>();
        String[] items = d.items != null ? d.items : new String[0];
        for (int i = 0; i < items.length; i++) {
            int which = i;
            if (d.checked != null) {
                rows.add(ListRow(P.of("key", "i" + i, "label", d.items[i]),
                        GlassSwitch(P.of("value", d.checked[i],
                                "onChange", (OnBool) v -> {
                                    if (d.onItem != null) {
                                        d.onItem.pick(which, v);
                                    }
                                }))));
            } else {
                rows.add(ListRow(P.of("key", "i" + i, "label", d.items[i], "onPress", (Runnable) () -> {
                            if (d.onItem != null) {
                                d.onItem.pick(which, true);
                            }
                        }),
                        i == d.selected ? Icon(P.of("name", "check", "size", 18, "color", t.accent)) : null));
            }
        }
        List<El> buttons = new ArrayList<>();
        for (GlassModel.Dialog.Button b : d.buttons) {
            boolean prominent = GlassModel.Dialog.PROMINENT.equals(b.style);
            boolean destructive = GlassModel.Dialog.DESTRUCTIVE.equals(b.style);
            buttons.add(GlassButton(P.of("key", b.label, "flex", 1, "variant", "filled", "label", b.label,
                    "fillColor", prominent ? t.accent : null,
                    "color", prominent ? "#fff" : destructive ? "#ff3b30" : null,
                    "onPress", b.onPress)));
        }
        El list = d.amount != null ? amountPicker(d.amount, t) : ListSection(P.of("mb", 0, "r", 26), rows);
        double maxList = t.H * 0.92 - 52 - 24 - (buttons.isEmpty() ? 0 : 64) - t.B;
        return Box(P.of(),
                d.message != null ? Text(P.of("size", 15, "ls", -0.15, "lh", 20, "lines", 8, "mb", 14,
                        "ml", 4, "mr", 4, "color", t.pal.label2), d.message) : null,
                items.length * 52.5 > maxList
                        ? GlassScrollView(P.of("h", maxList, "noSheetDrag", true), list)
                        : list,
                buttons.isEmpty() ? null : Row(P.of("gap", 10, "mt", 16), buttons));
    }

    /** − value + over a segmented unit picker, and a note under them. */
    private static El amountPicker(GlassModel.Amount a, KTheme t) {
        List<String> units = new ArrayList<>();
        Collections.addAll(units, a.units);
        return ListSection(P.of("mb", 0, "r", 26),
                ListPad(P.of("padY", 16),
                        Row(P.of("align", "center", "gap", 16),
                                GlassButton(P.of("key", "minus", "label", "−", "w", 56, "h", 44, "r", 22,
                                        "variant", "filled", "onPress", (Runnable) () -> {
                                            if (a.onValue != null && a.value > a.min) {
                                                a.onValue.on(a.value - 1);
                                            }
                                        })),
                                Text(P.of("flex", 1, "size", 34, "weight", 600, "align", "center", "lines", 1,
                                        "color", t.pal.label), String.valueOf(a.value)),
                                GlassButton(P.of("key", "plus", "label", "+", "w", 56, "h", 44, "r", 22,
                                        "variant", "filled", "onPress", (Runnable) () -> {
                                            if (a.onValue != null && a.value < a.max) {
                                                a.onValue.on(a.value + 1);
                                            }
                                        }))),
                        units.isEmpty() ? null : Box(P.of("mt", 16),
                                GlassSegmented(P.of("labels", units, "index", Math.max(0, a.unit),
                                        "onChange", (OnInt) i -> {
                                            if (a.onUnit != null) {
                                                a.onUnit.on(i);
                                            }
                                        }))),
                        a.note == null ? null : Text(P.of("size", 13, "lh", 17.55, "lines", 3, "mt", 12,
                                "color", a.error ? red(t) : t.pal.label2), a.note)));
    }
}
