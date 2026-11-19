namespace CodexMeter.Core.Services;

/// <summary>
/// The full-screen "What's new" page shown once after a big update (3.0 first). The same rule and
/// release as Android (<c>WhatsNew.java</c>) and the Apple apps (<c>WhatsNew.swift</c>); Windows and
/// Linux each list their own new features.
/// </summary>
public static class WhatsNew
{
    /// <summary>The release the page is about. Seen once per value; a smaller update keeps it.</summary>
    public const string Release = "3.0";
    public const string Title = "What's new in Codex Meter 3.0";
    public const string Button = "Continue";

    public enum Platform { Windows, Linux }

    /// <summary>One feature: an id each app maps to its own icon, a title and one line.</summary>
    public sealed record Feature(string Id, string Title, string Body);

    /// <summary>
    /// Whether to show the page on this launch: once per release, and only to someone who used the
    /// app before it (<paramref name="setUp"/>). A new install is marked seen instead (<see cref="AtLaunch"/>).
    /// </summary>
    public static bool ShouldShow(string? seen, bool setUp) => setUp && seen != Release;

    /// <summary>
    /// At launch: whether to show the page, and the settings to keep. Someone who finished setup or
    /// signed in before gets the page; a new install never does, so it is marked seen right away.
    /// </summary>
    public static (bool Show, AppSettings Settings) AtLaunch(AppSettings settings, int signedIn)
    {
        var setUp = settings.OnboardingDone || signedIn > 0;
        if (ShouldShow(settings.WhatsNewSeen, setUp)) return (true, settings);
        return (false, settings.WhatsNewSeen == Release ? settings : settings with { WhatsNewSeen = Release });
    }

    /// <summary>Every brand-new 3.0 feature on <paramref name="platform"/>, most important first.</summary>
    public static IReadOnlyList<Feature> Features(Platform platform)
    {
        var tray = platform == Platform.Windows ? "notification area" : "tray";
        return
        [
            new("providers", "Every AI service in one place",
                "Claude Code, Cursor, Copilot, Grok, OpenRouter and more join Codex, each with its own page."),
            new("priority", "Priority",
                "One page for what needs you now: limits to watch, used up, coming up, outages and new models."),
            new("resets", "Saved resets",
                "Claude Code resets and Codex reset credits, used for you before they expire or when you run low."),
            new("live", "Live usage in the " + tray,
                "The icon shows the AI app in front first, from a list you choose."),
            new("alerts", "Smarter alerts",
                "Hear when usage is back and when a limit refills early."),
            new("value", "Estimated value",
                "What your plan buys at API prices, from dated, sourced figures."),
            new("status", "Outages and slowdowns",
                "Official incidents and slow models, with a link to each status page."),
            new("updates", "Updates in the app",
                "Settings › Updates finds, checks and installs new versions for you."),
            new("settings", "Simpler settings",
                "The same groups on every device, with settings export and import."),
            new("devices", "On every device",
                "Codex Meter also runs on Android and Mac, with the same providers."),
        ];
    }
}
