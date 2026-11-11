package dev.bennett.codexmeter.glass;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Sample data shaped like the app's, for rendering the screens off-device. */
final class FakeSource implements GlassSource {
    private final Set<Runnable> subs = new LinkedHashSet<>();
    private String selected = "codex";
    private boolean usagePace = true;
    private boolean refreshOnLaunch = true;
    private boolean systemTheme = true;
    private boolean materialYou;
    private int design = 2;
    private int schedule = 1;
    GlassModel.Dialog dialog;
    String dialogScreen;
    /** pull-to-refresh calls */
    int refreshes;
    /** reorders dropped on a list */
    int moves;
    final List<String> tabOrderView() {
        return tabOrder;
    }
    /** only the first card, so the dashboard is shorter than the screen */
    boolean shortDashboard;

    FakeSource() {
        for (String id : new String[] {"codex", "claude_code", "cursor", "copilot"}) {
            GlassIcons.provider(id, pathOf(id));
        }
    }

    private static String pathOf(String id) {
        Path file = Paths.get("../app/src/main/res/drawable/ic_provider_" + id + ".xml");
        try {
            Matcher m = Pattern.compile("pathData=\"([^\"]+)\"")
                    .matcher(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
            return m.find() ? m.group(1) : "";
        } catch (IOException e) {
            return "";
        }
    }

    private void changed() {
        for (Runnable r : new ArrayList<>(subs)) {
            r.run();
        }
    }

    @Override
    public Runnable subscribe(Runnable onChange) {
        subs.add(onChange);
        return () -> subs.remove(onChange);
    }

    @Override
    public List<GlassModel.Tab> tabs() {
        return Arrays.asList(
                new GlassModel.Tab("codex", "Codex", "provider_codex"),
                new GlassModel.Tab("claude_code", "Claude", "provider_claude_code"),
                new GlassModel.Tab("cursor", "Cursor", "provider_cursor"),
                new GlassModel.Tab("copilot", "Copilot", "provider_copilot"));
    }

    @Override
    public String selectedTab() {
        return selected;
    }

    @Override
    public void selectTab(String id) {
        selected = id;
        changed();
    }

    /** the Priority tab, with Recent activity like the person's phone showed */
    boolean priority;
    /** Recent activity rows dismissed so far */
    final List<Integer> dismissed = new ArrayList<>();

    private GlassModel.Dashboard priorityDashboard() {
        GlassModel.Dashboard d = new GlassModel.Dashboard();
        d.title = "Priority";
        d.subtitle = "Claude Code and Cursor";
        GlassModel.Card news = new GlassModel.Card(GlassModel.Card.Kind.STATS);
        news.title = "Recent activity";
        news.detail = news.title;
        String[][] rows = {{"Fable reset used · Fable only refilled", "Claude Code · 32m ago"},
                {"1 Weekly reset added", "Claude Code · 9h ago"}};
        for (int i = 0; i < rows.length; i++) {
            if (dismissed.contains(i)) {
                continue;
            }
            int index = i;
            news.stats.add(rows[i]);
            news.statDismiss.add(() -> {
                dismissed.add(index);
                changed();
            });
        }
        news.dismissAll = () -> {
            dismissed.add(0);
            dismissed.add(1);
            changed();
        };
        news.summary = "Swipe a row left, or touch and hold it, to dismiss it.";
        if (!news.stats.isEmpty()) {
            d.cards.add(news);
        }
        GlassModel.Card used = new GlassModel.Card(GlassModel.Card.Kind.STATS);
        used.title = "Used up";
        used.detail = used.title;
        used.stats.add(new String[] {"Cursor · Monthly", "Resets Dec 1"});
        used.summary = "You'll get a notification when each one is back.";
        d.cards.add(used);
        return d;
    }

    /** how many times the dashboard model was built */
    int dashboardBuilds;

    @Override
    public GlassModel.Dashboard dashboard() {
        dashboardBuilds++;
        if (priority) {
            return priorityDashboard();
        }
        GlassModel.Dashboard d = new GlassModel.Dashboard();
        d.title = "codex".equals(selected) ? "Codex" : "Claude Code";
        d.subtitle = "Updated 2 minutes ago";
        GlassModel.Card five = new GlassModel.Card(GlassModel.Card.Kind.METER);
        five.title = "5-hour";
        five.icon = "clock";
        five.color = "#007aff";
        five.remaining = 0.72;
        five.value = "72%";
        five.detail = "Resets in 2 h 14 min";
        five.paceLabel = "At this pace it lasts";
        five.pace = "1d 6h";
        d.cards.add(five);
        GlassModel.Card week = new GlassModel.Card(GlassModel.Card.Kind.METER);
        week.title = "Weekly";
        week.icon = "calendar";
        week.color = "#5856d6";
        week.remaining = 0.19;
        week.value = "19%";
        week.detail = "Resets Sat 9:40 AM";
        week.paceLabel = "Running out early";
        week.pace = "2d 3h left";
        week.fast = true;
        d.cards.add(week);
        GlassModel.Card resets = new GlassModel.Card(GlassModel.Card.Kind.LINK);
        resets.title = "Reset credits";
        resets.icon = "bolt";
        resets.color = "#ff9500";
        resets.value = "2 available";
        resets.summary = "The next one expires in 6 days.";
        resets.onPress = () -> { };
        d.cards.add(resets);
        GlassModel.Card history = new GlassModel.Card(GlassModel.Card.Kind.HISTORY);
        history.title = "Usage history";
        double[] bars = new double[24];
        for (int i = 0; i < bars.length; i++) {
            bars[i] = 0.04 + 0.77 * Math.pow(i / 23.0, 1.2) + 0.03 * Math.sin(i);
        }
        history.bars = bars;
        history.value = "Weekly";
        history.color = "#007aff";
        history.onPress = () -> { };
        history.startLabel = "Window start";
        history.endLabel = "Now";
        history.summary = "How much of the weekly window was used, from its start until now.";
        d.cards.add(history);
        GlassModel.Card credits = new GlassModel.Card(GlassModel.Card.Kind.BALANCE);
        credits.title = "Usage credits";
        credits.icon = "creditcard";
        credits.color = "#34c759";
        credits.value = "1,240 credits";
        credits.summary = "Used after your plan's limits run out";
        d.cards.add(credits);
        if (shortDashboard) {
            d.cards.subList(1, d.cards.size()).clear();
        }
        return d;
    }

    @Override
    public boolean refreshing() {
        return false;
    }

    @Override
    public void refresh() {
        refreshes++;
    }

    @Override
    public List<MenuEntry> menu() {
        return Arrays.asList(
                new MenuEntry("Edit dashboard", "squarePencil", false, null),
                new MenuEntry("Settings", "gearshape", false, null));
    }

    private static GlassModel.Row link(String key, String title, String icon, String color, String page) {
        GlassModel.Row r = new GlassModel.Row(GlassModel.Row.Kind.LINK, key);
        r.title = title;
        r.icon = icon;
        r.color = color;
        r.link = page;
        return r;
    }

    @Override
    public GlassModel.Page page(String id) {
        switch (id) {
            case "root": {
                GlassModel.Page p = new GlassModel.Page("root", "Settings");
                GlassModel.Section accounts = new GlassModel.Section(null);
                GlassModel.Row acc = link("settings_accounts", "Accounts", "person2", "#007aff", "accounts");
                acc.value = "2";
                accounts.rows.add(acc);
                accounts.rows.add(link("settings_providers", "Dashboard tabs", "tabs", "#5856d6", null));
                p.sections.add(accounts);
                GlassModel.Section live = new GlassModel.Section(null);
                live.rows.add(link("settings_now_bar", "Now Bar", "waveform", "#ff9500", "now_bar"));
                live.rows.add(link("settings_notifications", "Notifications", "bell", "#ff3b30", "notifications"));
                live.rows.add(link("settings_lock_widgets", "Lock screen widgets", "lock", "#34c759", null));
                p.sections.add(live);
                GlassModel.Section usage = new GlassModel.Section(null);
                usage.rows.add(link("settings_refresh_usage", "Refresh & dashboard", "refresh", "#007aff", "refresh_usage"));
                usage.rows.add(link("settings_reset_credits", "Reset credits", "bolt", "#ff9f0a", "reset_credits"));
                usage.rows.add(link("settings_appearance", "Appearance", "paintbrush", "#5856d6", "appearance"));
                p.sections.add(usage);
                GlassModel.Section app = new GlassModel.Section(null);
                GlassModel.Row upd = link("settings_updates", "Updates", "download", "#8e8e93", "updates");
                upd.value = "Alpha";
                app.rows.add(upd);
                app.rows.add(link("settings_transfer", "Backup & transfer", "arrows", "#34c759", "transfer"));
                app.rows.add(link("settings_privacy", "Privacy", "hand", "#007aff", "privacy"));
                p.sections.add(app);
                GlassModel.Section about = new GlassModel.Section(null);
                about.rows.add(link("about_codex_meter", "About Codex Meter", "info", "#8e8e93", null));
                p.sections.add(about);
                return p;
            }
            case "appearance": {
                GlassModel.Page p = new GlassModel.Page(id, "Appearance");
                GlassModel.Section theme = new GlassModel.Section("Theme");
                GlassModel.Row seg = new GlassModel.Row(GlassModel.Row.Kind.SEGMENTED, "app_theme");
                seg.entries = new String[] {"Light", "Dark"};
                seg.selected = 0;
                seg.enabled = !systemTheme;
                theme.rows.add(seg);
                GlassModel.Row sys = new GlassModel.Row(GlassModel.Row.Kind.SWITCH, "theme_system_ui");
                sys.title = "System default";
                sys.checked = systemTheme;
                theme.rows.add(sys);
                GlassModel.Row you = new GlassModel.Row(GlassModel.Row.Kind.SWITCH, "material_you");
                you.title = "Material You";
                you.summary = "Use the system color palette when available";
                you.checked = materialYou;
                theme.rows.add(you);
                p.sections.add(theme);
                GlassModel.Section design = new GlassModel.Section("Design");
                GlassModel.Row d = new GlassModel.Row(GlassModel.Row.Kind.CHOICE, "app_design_ui");
                d.title = "App design";
                d.entries = new String[] {"One UI 8.5", "One UI 9", "Liquid Glass"};
                d.selected = this.design;
                design.rows.add(d);
                design.footer = "Liquid Glass uses Apple's iOS 26 design, drawn by Glassé.";
                p.sections.add(design);
                return p;
            }
            case "refresh_usage": {
                GlassModel.Page p = new GlassModel.Page(id, "Refresh & dashboard");
                GlassModel.Section refresh = new GlassModel.Section("Refresh");
                GlassModel.Row sched = new GlassModel.Row(GlassModel.Row.Kind.CHOICE, "refresh_schedule_ui");
                sched.title = "Refresh usage";
                sched.entries = new String[] {"Every 15 minutes", "Adaptive (recommended)", "Every hour", "Manually"};
                sched.selected = schedule;
                sched.summary = "Adaptive refreshes more often while you're using your allowance.";
                refresh.rows.add(sched);
                GlassModel.Row launch = new GlassModel.Row(GlassModel.Row.Kind.SWITCH, "refresh_on_launch");
                launch.title = "Refresh when opening the app";
                launch.checked = refreshOnLaunch;
                refresh.rows.add(launch);
                p.sections.add(refresh);
                GlassModel.Section dash = new GlassModel.Section("Dashboard");
                GlassModel.Row edit = new GlassModel.Row(GlassModel.Row.Kind.LINK, "dashboard_reorder_ui");
                edit.title = "Edit dashboard";
                edit.summary = "Choose and order the cards for each provider";
                dash.rows.add(edit);
                GlassModel.Row pace = new GlassModel.Row(GlassModel.Row.Kind.SWITCH, "usage_pace_enabled_ui");
                pace.title = "Usage estimates";
                pace.summary = "Estimate what will be left when each window resets";
                pace.checked = usagePace;
                dash.rows.add(pace);
                GlassModel.Row warn = new GlassModel.Row(GlassModel.Row.Kind.CHOICE, "usage_pace_sensitivity_ui");
                warn.title = "Fast-usage warnings";
                warn.entries = new String[] {"Off", "Relaxed", "Balanced", "Sensitive"};
                warn.selected = 2;
                warn.enabled = usagePace;
                dash.rows.add(warn);
                p.sections.add(dash);
                GlassModel.Section danger = new GlassModel.Section(null);
                GlassModel.Row clear = new GlassModel.Row(GlassModel.Row.Kind.ACTION, "clear_history");
                clear.title = "Clear local history";
                clear.destructive = true;
                danger.rows.add(clear);
                p.sections.add(danger);
                return p;
            }
            case "x:add_account":
                return addAccount();
            case "x:signin":
                return signIn();
            case "x:history":
                return history();
            case "x:tabs":
                return tabsPage();
            case "x:widget":
                return widgetPage();
            default: {
                GlassModel.Page p = new GlassModel.Page(id, id);
                return p;
            }
        }
    }

    @Override
    public void toggle(String page, String key, boolean on) {
        if ("usage_pace_enabled_ui".equals(key)) usagePace = on;
        if ("refresh_on_launch".equals(key)) refreshOnLaunch = on;
        if ("theme_system_ui".equals(key)) systemTheme = on;
        if ("material_you".equals(key)) materialYou = on;
        changed();
    }

    @Override
    public void choose(String page, String key, int index) {
        if ("app_design_ui".equals(key)) design = index;
        if ("refresh_schedule_ui".equals(key)) schedule = index;
        changed();
    }

    @Override
    public void press(String page, String key) {
        if ("clear_history".equals(key)) {
            GlassModel.Dialog d = new GlassModel.Dialog();
            d.title = "Clear local history?";
            d.message = "This removes the usage history saved on this device. Your account is not changed.";
            d.buttons.add(new GlassModel.Dialog.Button("Cancel", GlassModel.Dialog.CANCEL, null));
            d.buttons.add(new GlassModel.Dialog.Button("Clear", GlassModel.Dialog.DESTRUCTIVE, null));
            d.onClose = () -> {
                dialog = null;
                changed();
            };
            dialog = d;
            dialogScreen = CodexGlass.SETTINGS;
            changed();
        }
    }

    @Override
    public void closeSettings() {
    }

    @Override
    public Nav takeNav() {
        return null;
    }

    @Override
    public GlassModel.Dialog dialog(String screen) {
        return screen.equals(dialogScreen) ? dialog : null;
    }

    private final List<String> tabOrder = new ArrayList<>(Arrays.asList("Codex", "Claude Code", "Cursor", "Copilot"));
    private int tap;
    private double opacity = 0.5;

    private GlassModel.Page addAccount() {
        GlassModel.Page p = new GlassModel.Page("x:add_account", "Add account");
        p.intro = "Choose a service to connect. Each one gets its own tab on the dashboard.";
        GlassModel.Section search = new GlassModel.Section(null);
        GlassModel.Row field = new GlassModel.Row(GlassModel.Row.Kind.FIELD, "search");
        field.hint = "Search providers";
        field.value = "";
        search.rows.add(field);
        p.sections.add(search);
        GlassModel.Section list = new GlassModel.Section(null);
        String[][] rows = {
                {"copilot", "Copilot", "Monthly premium requests and chat · GitHub sign-in", null},
                {"claude_code", "Kimi Code", "5-hour and weekly limits · Kimi sign-in", null},
                {"cursor", "Grok", "SuperGrok credits for the billing period · xAI sign-in", "Experimental"},
                {"codex", "Devin", "Daily and weekly quota · Windsurf token", "Experimental"}};
        for (String[] r : rows) {
            GlassModel.Row row = new GlassModel.Row(GlassModel.Row.Kind.LINK, r[1]);
            row.title = r[1];
            row.summary = r[2];
            row.icon = "provider_" + r[0];
            row.plainIcon = true;
            row.tag = r[3];
            row.onPress = () -> { };
            list.rows.add(row);
        }
        p.sections.add(list);
        return p;
    }

    private GlassModel.Page signIn() {
        GlassModel.Page p = new GlassModel.Page("x:signin", "Copilot");
        GlassModel.Section hero = new GlassModel.Section(null);
        GlassModel.Row head = new GlassModel.Row(GlassModel.Row.Kind.INFO, "hero");
        head.title = "Connect Copilot";
        head.summary = "Monthly premium requests and chat";
        head.icon = "provider_copilot";
        head.plainIcon = true;
        hero.rows.add(head);
        GlassModel.Row how = new GlassModel.Row(GlassModel.Row.Kind.TEXT, "how");
        how.summary = "Codex Meter shows a short code. Enter it on GitHub’s sign-in page and approve; "
                + "this screen finishes on its own.";
        hero.rows.add(how);
        p.sections.add(hero);
        GlassModel.Section code = new GlassModel.Section(null);
        GlassModel.Row c = new GlassModel.Row(GlassModel.Row.Kind.CODE, "code");
        c.title = "Your code";
        c.value = "8F2K-QX4T";
        c.summary = "Waiting for you to enter it…";
        code.rows.add(c);
        p.sections.add(code);
        GlassModel.Section actions = new GlassModel.Section(null);
        GlassModel.Row open = new GlassModel.Row(GlassModel.Row.Kind.BUTTON, "open");
        open.title = "Copy code and open GitHub";
        open.onPress = () -> { };
        actions.rows.add(open);
        GlassModel.Row cancel = new GlassModel.Row(GlassModel.Row.Kind.ACTION, "cancel");
        cancel.title = "Cancel";
        cancel.onPress = () -> { };
        actions.rows.add(cancel);
        p.sections.add(actions);
        GlassModel.Section key = new GlassModel.Section("Or use an API key");
        GlassModel.Row field = new GlassModel.Row(GlassModel.Row.Kind.FIELD, "key");
        field.hint = "Paste your Copilot key";
        field.value = "";
        field.secret = true;
        field.onPress = () -> { };
        key.rows.add(field);
        p.sections.add(key);
        GlassModel.Section privacy = new GlassModel.Section(null);
        privacy.footer = "Sign-in details are encrypted with Android Keystore and only sent to GitHub.";
        p.sections.add(privacy);
        return p;
    }

    private GlassModel.Page history() {
        GlassModel.Page p = new GlassModel.Page("x:history", "Usage history");
        p.actions.add(new GlassModel.Action("Customize", "sliders", false, () -> { }));
        GlassModel.Section pick = new GlassModel.Section(null);
        GlassModel.Row seg = new GlassModel.Row(GlassModel.Row.Kind.SEGMENTED, "window");
        seg.entries = new String[] {"Weekly", "5-hour"};
        seg.selected = 0;
        seg.onChoose = i -> { };
        pick.rows.add(seg);
        p.sections.add(pick);
        GlassModel.Card chart = new GlassModel.Card(GlassModel.Card.Kind.CHART);
        chart.title = "Weekly window";
        GlassModel.Chart c = new GlassModel.Chart();
        c.label = "38% used so far";
        c.legend = true;
        c.sampleLabel = "42 samples";
        c.current = new double[] {0, 0, 0.1, 6, 0.2, 14, 0.3, 19, 0.42, 31, 0.55, 38};
        c.previous.add(new double[] {0, 0, 0.2, 10, 0.5, 40, 0.8, 70, 1, 82});
        c.previous.add(new double[] {0, 0, 0.3, 22, 0.6, 51, 1, 96});
        c.projection = new double[] {0.55, 38, 1, 71};
        c.hint = "Drag to inspect · tap a window to compare";
        c.windows.add(new Object[] {"Current window", "38% used · avg 0.4%/h", -1});
        c.windows.add(new Object[] {"Oct 20 – Oct 27", "96% used · avg 0.6%/h", 1});
        c.windows.add(new Object[] {"Oct 13 – Oct 20", "82% used · avg 0.5%/h", 0});
        chart.chart = c;
        GlassModel.Card stats = new GlassModel.Card(GlassModel.Card.Kind.STATS);
        stats.title = "Insights";
        stats.stats.add(new String[] {"Vs. earlier windows", "4 pts behind"});
        stats.stats.add(new String[] {"Runs out", "in 9 days"});
        stats.stats.add(new String[] {"Average window", "89% used"});
        GlassModel.Section week = new GlassModel.Section(null);
        week.cards.add(chart);
        week.cards.add(stats);
        p.sections.add(week);
        return p;
    }

    private GlassModel.Page tabsPage() {
        GlassModel.Page p = new GlassModel.Page("x:tabs", "Dashboard tabs");
        p.intro = "Tap Edit to reorder, or touch and hold a tab. Switch off to hide one.";
        GlassModel.Section list = new GlassModel.Section(null);
        list.footer = "With five or more on, the first three get tabs and the rest go under More.";
        String[] ids = {"codex", "claude_code", "cursor", "copilot"};
        List<String> names = Arrays.asList("Codex", "Claude Code", "Cursor", "Copilot");
        for (String name : tabOrder) {
            GlassModel.Row r = new GlassModel.Row(GlassModel.Row.Kind.SWITCH, name);
            r.title = name;
            r.icon = "provider_" + ids[names.indexOf(name)];
            r.plainIcon = true;
            r.checked = !"Copilot".equals(name);
            r.summary = "Copilot".equals(name) ? "Not signed in" : null;
            list.rows.add(r);
        }
        list.onMove = (from, to) -> {
            tabOrder.add(to, tabOrder.remove(from));
            moves++;
            changed();
        };
        p.sections.add(list);
        return p;
    }

    private GlassModel.Page widgetPage() {
        GlassModel.Page p = new GlassModel.Page("x:widget", "Customize widget");
        p.actions.add(new GlassModel.Action("Save", null, true, () -> { }));
        GlassModel.Section look = new GlassModel.Section("Appearance");
        GlassModel.Row bg = new GlassModel.Row(GlassModel.Row.Kind.SWITCH, "bg");
        bg.title = "Background";
        bg.checked = true;
        look.rows.add(bg);
        GlassModel.Row slider = new GlassModel.Row(GlassModel.Row.Kind.SLIDER, "opacity");
        slider.title = "Opacity";
        slider.steps = 3;
        slider.fraction = opacity;
        slider.onSlide = v -> {
            opacity = v;
            changed();
        };
        look.rows.add(slider);
        GlassModel.Row theme = new GlassModel.Row(GlassModel.Row.Kind.CHOICE, "theme");
        theme.title = "Theme";
        theme.entries = new String[] {"Match app", "Light", "Dark"};
        theme.selected = 0;
        look.rows.add(theme);
        p.sections.add(look);
        GlassModel.Section tapSection = new GlassModel.Section("Widget tap action");
        String[] labels = {"Open Codex Meter", "Refresh usage", "Use reset if available"};
        for (int i = 0; i < labels.length; i++) {
            int which = i;
            GlassModel.Row r = new GlassModel.Row(GlassModel.Row.Kind.RADIO, "tap" + i);
            r.title = labels[i];
            r.checked = i == tap;
            r.onPress = () -> {
                tap = which;
                changed();
            };
            tapSection.rows.add(r);
        }
        p.sections.add(tapSection);
        GlassModel.Section save = new GlassModel.Section(null);
        GlassModel.Row button = new GlassModel.Row(GlassModel.Row.Kind.BUTTON, "use");
        button.title = "Use 1 reset";
        save.rows.add(button);
        p.sections.add(save);
        return p;
    }

    /** The Add reminder time sheet. */
    void showAmount(String screen) {
        GlassModel.Dialog d = new GlassModel.Dialog();
        d.title = "Add reminder time";
        d.message = "Notify me this long before each available reset credit expires.";
        GlassModel.Amount a = new GlassModel.Amount();
        a.value = 2;
        a.units = new String[] {"Minutes", "Hours", "Days", "Weeks"};
        a.unit = 1;
        d.amount = a;
        d.buttons.add(new GlassModel.Dialog.Button("Cancel", GlassModel.Dialog.CANCEL, null));
        d.buttons.add(new GlassModel.Dialog.Button("Add", GlassModel.Dialog.PROMINENT, null));
        dialog = d;
        dialogScreen = screen;
        changed();
    }
}
