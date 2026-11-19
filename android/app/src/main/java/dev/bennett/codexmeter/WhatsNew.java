package dev.bennett.codexmeter;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The full-screen "What's new" page shown once after a big update (3.0 first), in every design.
 * Pure Java: what it lists and when it shows, so the self-tests can check both. Each design draws it
 * with its own full-screen layout ({@link WhatsNewActivity}).
 *
 * <p>Only big releases get a page. A smaller update keeps {@link #RELEASE}, so nobody sees it twice;
 * the next big one changes {@link #RELEASE} and its {@link #FEATURES}. The same list and rule live in
 * the Apple ({@code WhatsNew.swift}) and desktop ({@code WhatsNew.cs}) cores, each with that platform's
 * features.
 */
final class WhatsNew {
    private WhatsNew() {}

    /** The release the page is about. Seen once per value. */
    static final String RELEASE = "3.0";
    static final String TITLE = "What's new in Codex Meter 3.0";
    static final String BUTTON = "Continue";

    /** One feature: an icon name each design maps to its own icon, a title and one line. */
    static final class Feature {
        final String icon;
        final String title;
        final String body;

        Feature(String icon, String title, String body) {
            this.icon = icon;
            this.title = title;
            this.body = body;
        }
    }

    /** Every brand-new 3.0 feature on Android, most important first. */
    static final List<Feature> FEATURES = Collections.unmodifiableList(Arrays.asList(
            new Feature("providers", "Every AI service in one place",
                    "Claude Code, Cursor, Copilot, Grok, OpenRouter and more join Codex, each with "
                            + "its own tab."),
            new Feature("priority", "Priority",
                    "One tab for what needs you now: limits to watch, used up, coming up, outages "
                            + "and new models."),
            new Feature("designs", "Four designs",
                    "Liquid Glass, One UI 9, Material 3 Expressive and One UI 8.5, in Settings › "
                            + "Appearance."),
            new Feature("resets", "Saved resets",
                    "Claude Code resets and Codex reset credits, used for you before they expire "
                            + "or when you run low."),
            new Feature("live", "Live usage for any service",
                    "The Now Bar or a live notification, starting on its own while an AI app is "
                            + "open."),
            new Feature("alerts", "Smarter alerts",
                    "Hear when usage is back, when a limit refills early and when new models "
                            + "land."),
            new Feature("value", "Estimated value",
                    "What your plan buys at API prices, from dated, sourced figures."),
            new Feature("status", "Outages and model speed",
                    "Official incidents on the dashboard, and each model's live speed and "
                            + "uptime."),
            new Feature("widgets", "Widgets for every service",
                    "Home and lock screen widgets for any provider, in every design."),
            new Feature("duo", "Made for foldables",
                    "The Duo layout keeps Back, buttons and tabs in a column beside the page."),
            new Feature("desktop", "On your computer too",
                    "Codex Meter for Windows, Mac and Linux, with your limits in the tray or menu "
                            + "bar."),
            new Feature("settings", "Simpler settings",
                    "The same groups on every device, with settings export and import.")));

    /**
     * Whether to show the page on this launch: once per {@link #RELEASE}, and only to someone who
     * used the app before it (a new install finishes setup and is marked seen instead).
     *
     * @param seen the release last shown, or null for none
     * @param setUp whether setup was already finished before this launch
     */
    static boolean shouldShow(String seen, boolean setUp) {
        return setUp && !RELEASE.equals(seen);
    }
}
