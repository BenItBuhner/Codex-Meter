package dev.bennett.codexmeter;

import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.text.Html;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import dev.bennett.codexmeter.glass.GlassModel;
import dev.bennett.codexmeter.glass.GlassModel.Card;
import dev.bennett.codexmeter.glass.GlassModel.Row;
import dev.bennett.codexmeter.glass.GlassModel.Section;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Liquid Glass pages beyond Settings: Dashboard tabs, Lock screen widgets, About, Edit
 * dashboard, usage history, reset credits, updates, release history and widget settings. Each
 * page does what its One UI activity does, with the same preferences and APIs, and shows it with
 * Glassé's list rows, cards, switches and buttons. Pages are opened by the intents that would
 * open those activities ({@link #pageFor}).
 */
final class GlassScreens {
    static final String PREFIX = "x:";

    /** One page: builds its model from the app's state each time it is drawn. */
    abstract static class Screen {
        final GlassScreens screens;

        Screen(GlassScreens screens) {
            this.screens = screens;
        }

        abstract GlassModel.Page page();

        /** The page came on screen; {@code intent} is what opened it, when an intent did. */
        void opened(Intent intent) {
        }

        /** The page came to the top of the stack (opened, or uncovered by back). */
        void shown() {
        }

        /** The activity came back to the front while this page was showing. */
        void resumed() {
        }

        AppCompatActivity activity() {
            return screens.activity;
        }

        void changed() {
            screens.app.changed();
        }
    }

    final AppCompatActivity activity;
    final GlassApp app;
    final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Map<String, Screen> cache = new HashMap<>();

    GlassScreens(AppCompatActivity activity, GlassApp app) {
        this.activity = activity;
        this.app = app;
    }

    void dispose() {
        executor.shutdownNow();
    }

    /** The glass page for an intent that opens one of the ported activities, or null. */
    static String pageFor(Intent intent) {
        if (intent == null || intent.getComponent() == null) {
            return null;
        }
        String name = intent.getComponent().getClassName();
        if (name.equals(ProvidersActivity.class.getName())) {
            return PREFIX + "tabs";
        }
        if (name.equals(LockWidgetsActivity.class.getName())) {
            return PREFIX + "lock_widgets";
        }
        if (name.equals(AboutActivity.class.getName())) {
            return PREFIX + "about";
        }
        if (name.equals(UsageHistoryActivity.class.getName())) {
            return PREFIX + "history:" + providerOf(intent, UsageHistoryActivity.EXTRA_PROVIDER);
        }
        if (name.equals(ResetCreditActivity.class.getName())) {
            UsageProvider provider = UsageProvider.CLAUDE_CODE.id.equals(
                    intent.getStringExtra(MainActivity.EXTRA_PROVIDER))
                    ? UsageProvider.CLAUDE_CODE : UsageProvider.CODEX;
            return PREFIX + "reset:" + provider.id;
        }
        if (name.equals(DashboardReorderActivity.class.getName())) {
            return PREFIX + "reorder:" + providerOf(intent, DashboardReorderActivity.EXTRA_PROVIDER);
        }
        if (name.equals(UpdateActivity.class.getName())) {
            return PREFIX + "update";
        }
        if (name.equals(ReleaseHistoryActivity.class.getName())) {
            return PREFIX + "releases";
        }
        if (name.equals(LockWidgetConfigActivity.class.getName())) {
            return PREFIX + "lock_widget:" + intent.getIntExtra(
                    AppWidgetManager.EXTRA_APPWIDGET_ID, 0);
        }
        return null;
    }

    private static String providerOf(Intent intent, String extra) {
        return UsageProvider.fromId(intent.getStringExtra(extra)).id;
    }

    GlassModel.Page page(String id) {
        Screen screen = screen(id);
        return screen == null ? null : screen.page();
    }

    void opened(String id, Intent intent) {
        Screen screen = screen(id);
        if (screen != null) {
            screen.opened(intent);
        }
    }

    Screen screen(String id) {
        if (id == null || !id.startsWith(PREFIX)) {
            return null;
        }
        Screen screen = cache.get(id);
        if (screen == null) {
            screen = create(id.substring(PREFIX.length()));
            if (screen != null) {
                cache.put(id, screen);
            }
        }
        return screen;
    }

    private Screen create(String id) {
        String[] parts = id.split(":", 2);
        String arg = parts.length > 1 ? parts[1] : "";
        switch (parts[0]) {
            case "tabs":
                return new Tabs(this);
            case "lock_widgets":
                return new LockWidgets(this);
            case "about":
                return new About(this);
            case "reorder":
                return new Reorder(this, UsageProvider.fromId(arg));
            case "releases":
                return new Releases(this);
            case "history":
                return new GlassHistoryScreen(this, UsageProvider.fromId(arg));
            case "reset":
                return new GlassResetScreen(this, UsageProvider.fromId(arg));
            case "update":
                return new GlassUpdateScreen(this);
            case "lock_widget":
                return new GlassWidgetScreens.Lock(this, parseInt(arg), app::pop);
            case "widget":
                return new GlassWidgetScreens.Home(this, parseInt(arg), app::pop);
            case "add_account":
                return activity instanceof AddAccountActivity ? new Hosted(this) {
                    @Override
                    GlassModel.Page page() {
                        return ((AddAccountActivity) activity()).glassPage();
                    }
                } : null;
            case "signin":
                return activity instanceof SignInActivity ? new Hosted(this) {
                    @Override
                    GlassModel.Page page() {
                        return ((SignInActivity) activity()).glassPage();
                    }
                } : null;
            default:
                return null;
        }
    }

    /** A page whose activity builds it (Add account, sign-in): its model comes from that activity. */
    private abstract static class Hosted extends Screen {
        Hosted(GlassScreens screens) {
            super(screens);
        }
    }

    /** Registers a screen made by its own activity (widget settings), under {@code id}. */
    void put(String id, Screen screen) {
        cache.put(id, screen);
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ helpers

    static Row row(Row.Kind kind, String key, String title, String summary) {
        Row r = new Row(kind, key);
        r.title = title == null ? "" : title;
        r.summary = summary == null || summary.isEmpty() ? null : summary;
        return r;
    }

    static Row text(String key, String text) {
        return row(Row.Kind.TEXT, key, "", text);
    }

    static Section section(String header) {
        return new Section(header);
    }

    static Card note(String title, String text) {
        Card c = new Card(Card.Kind.NOTE);
        c.title = title;
        c.summary = text;
        return c;
    }

    /** A card section: cards in place of rows. */
    static Section cards(Card... cards) {
        Section s = new Section(null);
        Collections.addAll(s.cards, cards);
        return s;
    }

    static byte[] png(Bitmap bitmap) {
        if (bitmap == null) {
            return null;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        return out.toByteArray();
    }

    static byte[] png(Context context, int drawable, int sizePx) {
        Drawable d = context.getDrawable(drawable);
        if (d == null) {
            return null;
        }
        Bitmap bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        d.setBounds(0, 0, sizePx, sizePx);
        d.draw(canvas);
        return png(bitmap);
    }

    static String plainNotes(String markdown) {
        String html = ReleaseNotesMarkdown.toHtml(markdown);
        if (html.isEmpty()) {
            return "";
        }
        CharSequence text = Build.VERSION.SDK_INT >= 24
                ? Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT) : Html.fromHtml(html);
        return text.toString().trim();
    }

    void openUrl(String url, String failure) {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (RuntimeException e) {
            Toast.makeText(activity, failure, Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------------ Dashboard tabs

    /** Which providers get a tab, and in what order (ProvidersActivity). */
    static final class Tabs extends Screen {
        private List<UsageProvider> items;

        Tabs(GlassScreens screens) {
            super(screens);
        }

        @Override
        void shown() {
            items = null;
        }

        private List<UsageProvider> items() {
            if (items == null) {
                items = new ArrayList<>();
                List<UsageProvider> enabled = ProviderPreferences.getEnabled(activity());
                for (UsageProvider provider : ProviderPreferences.getOrder(activity())) {
                    if (enabled.contains(provider) || ProviderAccounts.isSignedIn(activity(), provider)) {
                        items.add(provider);
                    }
                }
            }
            return items;
        }

        @Override
        GlassModel.Page page() {
            Context c = activity();
            GlassModel.Page page = new GlassModel.Page(PREFIX + "tabs", "Dashboard tabs");
            page.intro = "Drag to reorder. Switch off to hide a tab.";
            Section list = section(null);
            list.footer = "With five or more on, the first three get tabs and the rest go under More.";
            List<UsageProvider> enabled = ProviderPreferences.getEnabled(c);
            for (UsageProvider provider : items()) {
                boolean signedIn = ProviderAccounts.isSignedIn(c, provider);
                Row r = row(Row.Kind.SWITCH, provider.id, provider.displayName,
                        signedIn ? null : "Not signed in");
                r.icon = "provider_" + provider.id;
                r.plainIcon = true;
                r.checked = enabled.contains(provider);
                r.onToggle = on -> {
                    if (!ProviderPreferences.setEnabled(c, provider, on)) {
                        Toast.makeText(c, "Keep at least one provider on.", Toast.LENGTH_SHORT).show();
                    } else {
                        DiagnosticLog.info(c, "user", "provider_enabled_changed", "provider", provider.id);
                    }
                    changed();
                };
                list.rows.add(r);
            }
            list.onMove = (from, to) -> {
                List<UsageProvider> order = items();
                order.add(to, order.remove(from));
                ProviderPreferences.setOrder(c, order);
                DiagnosticLog.info(c, "user", "provider_order_changed");
                changed();
            };
            page.sections.add(list);
            return page;
        }
    }

    // ------------------------------------------------------------------ Lock screen widgets

    static final class LockWidgets extends Screen {
        LockWidgets(GlassScreens screens) {
            super(screens);
        }

        @Override
        GlassModel.Page page() {
            Context c = activity();
            GlassModel.Page page = new GlassModel.Page(PREFIX + "lock_widgets", "Lock screen widgets");
            List<SamsungLockWidgetSupport.LockInstance> widgets = SamsungLockWidgetSupport.placedWidgets(c);
            if (widgets.isEmpty()) {
                page.intro = "No Codex Meter widgets on your lock screen yet. Touch and hold the lock "
                        + "screen, tap Edit, then Widgets, and add Gauge or Gauges from Codex Meter. "
                        + "They come back here to be set up.";
                return page;
            }
            page.intro = "Tap a widget to choose its providers and meters. Until you do, it shows the "
                    + "providers you're signed in to, in your tab order.";
            Section list = section(null);
            for (SamsungLockWidgetSupport.LockInstance widget : widgets) {
                int slots = SamsungLockWidgetSupport.slotCount(c, widget.appWidgetId) > 1
                        ? WidgetProviders.MAX_LOCK_WIDE : 1;
                String summary = WidgetProviders.summary(ProviderPreferences.getLockWidgetProviders(
                        c, widget.appWidgetId, slots));
                if (!ProviderPreferences.hasSavedWidgetProviders(c, widget.appWidgetId)) {
                    summary += " · automatic";
                }
                Row r = row(Row.Kind.LINK, "w" + widget.appWidgetId, widget.label(), summary);
                r.onPress = () -> screens.app.openPage(PREFIX + "lock_widget:" + widget.appWidgetId, null);
                list.rows.add(r);
            }
            page.sections.add(list);
            return page;
        }
    }

    // ------------------------------------------------------------------ About

    static final class About extends Screen {
        private static final int DIAGNOSTIC_TAPS = 7;
        private int versionTaps;
        private byte[] icon;
        private byte[] benit;
        private byte[] josh;

        About(GlassScreens screens) {
            super(screens);
        }

        @Override
        GlassModel.Page page() {
            AppCompatActivity c = activity();
            int px = Math.round(120 * c.getResources().getDisplayMetrics().density);
            if (icon == null) {
                icon = png(c, R.mipmap.ic_launcher, px);
                benit = png(c, R.drawable.benit_github_avatar, px);
                josh = png(c, R.drawable.codex_profile_avatar, px);
            }
            GlassModel.Page page = new GlassModel.Page(PREFIX + "about", "About");
            page.actions.add(new GlassModel.Action("GitHub", null, false,
                    () -> screens.openUrl(GitHubReleaseSource.REPOSITORY_URL, "No browser can open GitHub.")));
            page.actions.add(new GlassModel.Action("App info", "info", false, () -> c.startActivity(
                    new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + c.getPackageName())))));

            Section app = section(null);
            Row card = row(Row.Kind.INFO, "app", "Codex Meter",
                    c.getString(R.string.about_version, Ui.versionName(c)));
            card.image = icon;
            card.imageSize = 60;
            card.onPress = this::onVersionTap;
            app.rows.add(card);
            page.sections.add(app);

            Section credits = section("Credits");
            credits.rows.add(person("benit", "BenIt Buhner", "App creator and AI geek", benit,
                    "https://github.com/BenItBuhner"));
            credits.rows.add(person("josh", "That Josh Guy", "App and Icon designer", josh,
                    "https://tjg.gg"));
            page.sections.add(credits);

            Section dependencies = section("Dependencies");
            Row oneUi = row(Row.Kind.LINK, "oneui", "One UI Design Library",
                    "The library that makes this app so pretty.");
            oneUi.icon = "paintbrush";
            oneUi.color = "#5856d6";
            oneUi.onPress = () -> screens.openUrl("https://github.com/tribalfs/oneui-design",
                    "No browser can open the link.");
            dependencies.rows.add(oneUi);
            Row glasse = row(Row.Kind.LINK, "glasse", "Glassé",
                    "The Liquid Glass you're looking at.");
            glasse.icon = "sparkles";
            glasse.color = "#007aff";
            glasse.onPress = () -> screens.openUrl("https://github.com/BenItBuhner/Glasse",
                    "No browser can open the link.");
            dependencies.rows.add(glasse);
            Row openAi = row(Row.Kind.LINK, "openai", "OpenAI API",
                    "This app would be pretty useless without it.");
            openAi.icon = "provider_codex";
            openAi.plainIcon = true;
            openAi.onPress = () -> screens.openUrl("https://openai.com", "No browser can open the link.");
            dependencies.rows.add(openAi);
            page.sections.add(dependencies);
            return page;
        }

        private Row person(String key, String name, String role, byte[] avatar, String url) {
            Row r = row(Row.Kind.LINK, key, name, role);
            r.image = avatar;
            r.roundImage = true;
            r.imageSize = 44;
            r.onPress = () -> screens.openUrl(url, "No browser can open the link.");
            return r;
        }

        private void onVersionTap() {
            Context c = activity();
            versionTaps++;
            int remaining = DIAGNOSTIC_TAPS - versionTaps;
            if (remaining <= 0) {
                Toast.makeText(c, "Diagnostics unlocked.", Toast.LENGTH_SHORT).show();
                activity().startActivity(SettingsActivity.diagnosticsIntent(c));
                versionTaps = 0;
            } else if (remaining <= 3) {
                Toast.makeText(c, remaining + " more tap" + (remaining == 1 ? "" : "s")
                        + " for diagnostics.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    // ------------------------------------------------------------------ Edit dashboard

    /** Which cards a provider's dashboard shows, and in what order (DashboardReorderActivity). */
    static final class Reorder extends Screen {
        private final UsageProvider provider;
        private final List<String[]> items = new ArrayList<>();

        Reorder(GlassScreens screens, UsageProvider provider) {
            super(screens);
            this.provider = provider;
        }

        @Override
        void shown() {
            items.clear();
        }

        @Override
        GlassModel.Page page() {
            Context c = activity();
            if (items.isEmpty()) {
                build(c);
            }
            GlassModel.Page page = new GlassModel.Page(PREFIX + "reorder:" + provider.id, "Edit dashboard");
            page.intro = provider == UsageProvider.CODEX
                    ? "Drag the handles to arrange your Codex cards and use the switches to hide the "
                            + "ones you don't need. Model-specific limits such as GPT-5.3-Codex-Spark "
                            + "appear here automatically once OpenAI reports them for your account."
                    : "Drag the handles to arrange your " + provider.displayName + " cards and use "
                            + "the switches to hide the ones you don't need. Each provider keeps its "
                            + "own layout.";
            Section list = section(null);
            list.footer = provider == UsageProvider.CODEX
                    ? "Changes are saved instantly. Usage-credit balance and reset credits stay hidden "
                            + "when they have nothing to show (zero or below); 5-hour, weekly, "
                            + "monthly, and usage-history cards appear only while OpenAI reports data "
                            + "for them; and the monthly credit limit appears only for accounts under "
                            + "workspace spend controls — no matter where each card is placed or "
                            + "whether its switch is on."
                    : "Changes are saved instantly. Cards appear only while " + provider.vendorName
                            + " reports data for them, and new ones show up here automatically.";
            for (String[] item : items) {
                Row r = row(Row.Kind.SWITCH, item[0], item[1], item[2]);
                r.checked = ProviderPreferences.isDashboardSectionVisible(c, provider, item[0]);
                r.onToggle = on -> {
                    ProviderPreferences.setDashboardSectionVisible(c, provider, item[0], on);
                    changed();
                };
                list.rows.add(r);
            }
            list.onMove = (from, to) -> {
                items.add(to, items.remove(from));
                List<String> order = new ArrayList<>();
                for (String[] item : items) {
                    order.add(item[0]);
                }
                ProviderPreferences.setDashboardOrder(c, provider, order);
                changed();
            };
            page.sections.add(list);
            return page;
        }

        private void add(String key, String title, String summary) {
            items.add(new String[] {key, title, summary});
        }

        private void build(Context c) {
            if (provider != UsageProvider.CODEX) {
                buildProvider(c);
                return;
            }
            UsageSnapshot snapshot = AppPreferences.loadSnapshot(c);
            List<UsageLimit> limits = snapshot == null
                    ? Collections.<UsageLimit>emptyList() : snapshot.additionalLimits;
            List<String> ordered = DashboardSections.resolveOrder(
                    ProviderPreferences.getDashboardOrder(c, provider),
                    DashboardSections.defaultOrder(limits));
            for (String key : ordered) {
                if (DashboardSections.FIVE_HOUR.equals(key)) {
                    add(key, "5-hour limit", "Rolling 5-hour Codex window");
                } else if (DashboardSections.WEEKLY.equals(key)) {
                    add(key, "Weekly limit", "Rolling 7-day Codex window");
                } else if (DashboardSections.MONTHLY.equals(key)) {
                    add(key, "Monthly limit", "Rolling ~30-day Codex window (free tier)");
                } else if (DashboardSections.SPEND_CONTROL.equals(key)) {
                    add(key, "Monthly credit limit",
                            "Workspace spend limit · shown only when OpenAI reports one");
                } else if (DashboardSections.USAGE_CREDITS.equals(key)) {
                    add(key, "Usage-credit balance", "Hidden automatically at a zero or negative balance");
                } else if (DashboardSections.USAGE_HISTORY.equals(key)) {
                    add(key, "Usage history", "Shown only when a 5-hour or weekly window is available");
                } else if (DashboardSections.RESET_CREDITS.equals(key)) {
                    add(key, "Reset credits", "Hidden automatically when no resets are available");
                } else {
                    add(key, limitName(limits, key), "Model-specific limit · detected automatically");
                }
            }
        }

        private void buildProvider(Context c) {
            UsageSnapshot snapshot = ProviderPreferences.loadSnapshot(c, provider);
            List<UsageLimit> limits = snapshot == null
                    ? Collections.<UsageLimit>emptyList() : snapshot.additionalLimits;
            List<String> sections = new ArrayList<>(DashboardSections.providerSections(snapshot,
                    provider.usualDashboardSections()));
            if (provider == UsageProvider.CLAUDE_CODE
                    && ProviderPreferences.loadSavedResets(c, provider) != null) {
                sections.add(DashboardSections.RESET_CREDITS);
            }
            List<String> ordered = DashboardSections.resolveOrder(
                    ProviderPreferences.getDashboardOrder(c, provider), sections);
            boolean cursor = provider == UsageProvider.CURSOR;
            for (String key : ordered) {
                if (DashboardSections.FIVE_HOUR.equals(key)) {
                    add(key, "5-hour limit", "Rolling 5-hour " + provider.displayName + " window");
                } else if (DashboardSections.WEEKLY.equals(key)) {
                    add(key, cursor ? "Weekly usage" : "Weekly limit",
                            cursor ? "Your plan's usage, refreshed weekly"
                                    : "Rolling 7-day " + provider.displayName + " window");
                } else if (DashboardSections.MONTHLY.equals(key)) {
                    add(key, cursor ? "Monthly usage" : "Monthly limit",
                            cursor ? "Your plan's usage for the billing month"
                                    : "Monthly " + provider.displayName + " window");
                } else if (DashboardSections.USAGE_HISTORY.equals(key)) {
                    add(key, "Usage history", "Burn charts from usage samples kept on this device");
                } else if (DashboardSections.RESET_CREDITS.equals(key)) {
                    add(key, "Reset credits", "Hidden automatically when no resets are available");
                } else {
                    add(key, limitName(limits, key), "Reported by " + provider.vendorName + " for your plan");
                }
            }
        }

        private static String limitName(List<UsageLimit> limits, String key) {
            for (UsageLimit limit : limits) {
                if (limit != null && DashboardSections.limitKey(limit).equals(key)) {
                    return limit.displayName();
                }
            }
            return "Additional limit";
        }
    }

    // ------------------------------------------------------------------ Release history

    static final class Releases extends Screen {
        private List<GitHubRelease> releases;
        private boolean failed;
        private boolean loading;

        Releases(GlassScreens screens) {
            super(screens);
        }

        @Override
        void opened(Intent intent) {
            Context app = activity().getApplicationContext();
            List<GitHubRelease> cached = UpdatePreferences.releases(app);
            releases = cached.isEmpty() ? null : cached;
            loading = true;
            screens.executor.execute(() -> {
                List<GitHubRelease> result;
                boolean error = false;
                try {
                    result = ReleaseUpdateClient.check(app);
                } catch (Exception e) {
                    result = UpdatePreferences.releases(app);
                    error = true;
                }
                List<GitHubRelease> done = result;
                boolean failure = error;
                activity().runOnUiThread(() -> {
                    releases = done;
                    failed = failure;
                    loading = false;
                    changed();
                });
            });
        }

        @Override
        GlassModel.Page page() {
            Context c = activity();
            GlassModel.Page page = new GlassModel.Page(PREFIX + "releases", "Release history");
            if (releases == null && loading) {
                Card wait = new Card(Card.Kind.MESSAGE);
                wait.title = "Loading release history…";
                page.sections.add(cards(wait));
                return page;
            }
            String installed = UpdatePreferences.installedVersion(c);
            page.sections.add(cards(note("Installed version " + installed,
                    "Newer and matching releases from Codex Meter "
                            + ReleaseUpdatePolicy.FIRST_IN_APP_UPDATE_VERSION
                            + " onward are checksum- and signature-verified in the app. Releases before "
                            + ReleaseUpdatePolicy.FIRST_IN_APP_UPDATE_VERSION
                            + " are irreversible and must be installed from GitHub because those builds lack "
                            + "working in-app updates. Other older versions still require uninstalling first, "
                            + "which removes local data and widgets. Alpha builds are the exception: they "
                            + "share the stable version code, so the newest stable release always installs "
                            + "back in place.")));
            if (failed) {
                Card error = new Card(Card.Kind.ERROR);
                error.title = UpdatePreferences.lastError(c);
                page.sections.add(cards(error));
            }
            if (releases == null || releases.isEmpty()) {
                page.sections.add(cards(note("No installable releases yet",
                        "GitHub currently has no published release containing both the expected APK "
                                + "and SHA256SUMS.txt.")));
                return page;
            }
            Section list = section(null);
            for (GitHubRelease release : releases) {
                list.cards.add(release(c, release, installed));
            }
            Section heading = section("Published versions");
            heading.rows.add(text("published", releases.size() + " release"
                    + (releases.size() == 1 ? "" : "s") + " on GitHub."));
            page.sections.add(heading);
            page.sections.add(list);
            return page;
        }

        private Card release(Context c, GitHubRelease release, String installed) {
            int comparison = ReleaseVersion.compare(release.version, installed);
            boolean irreversible = ReleaseUpdatePolicy.isIrreversible(release.version);
            boolean returnToStable = UpdateChannel.isReturnToStable(release, installed);
            String suffix = release.prerelease ? " · Prerelease"
                    : irreversible ? " · Irreversible"
                    : comparison > 0 ? " · Update"
                    : comparison == 0 ? " · Installed" : " · Older";
            String published = release.publishedAt.length() >= 10
                    ? release.publishedAt.substring(0, 10) : "Unknown date";
            Card card = new Card(Card.Kind.UPDATE);
            card.title = release.name;
            card.summary = "v" + release.version + suffix + " · " + published;
            card.warning = irreversible;
            StringBuilder detail = new StringBuilder();
            if (irreversible) {
                detail.append(ReleaseUpdatePolicy.irreversibleSummary())
                        .append(". Update manually from the GitHub release page.");
            }
            if (!release.notes.isEmpty()) {
                if (detail.length() > 0) {
                    detail.append("\n\n");
                }
                detail.append("What’s new\n").append(plainNotes(release.notes));
            }
            card.detail = detail.length() == 0 ? null : detail.toString();
            if (irreversible) {
                card.button = "Open on GitHub";
                card.onPress = () -> screens.openUrl(release.pageUrl,
                        "No browser can open the GitHub release page.");
            } else {
                card.button = returnToStable ? "View stable return"
                        : comparison < 0 ? "View downgrade" : comparison == 0 ? "View reinstall"
                        : "View update";
                card.onPress = () -> screens.app.start(new Intent(c, UpdateActivity.class)
                        .putExtra(UpdateActivity.EXTRA_VERSION, release.version));
            }
            return card;
        }
    }
}
