namespace CodexMeter.Core.Providers;

/// <summary>How a provider's account is connected.</summary>
public enum SignInStyle
{
    /// <summary>Browser sign-in that returns to a local loopback port (Codex, Claude Code).</summary>
    Loopback,
    /// <summary>Browser approval the app polls for (Cursor).</summary>
    BrowserPoll,
}

/// <summary>
/// A coding-agent service whose limits Codex Meter shows. Ids are shared with the Android and
/// iOS apps (<c>codex</c>, <c>claude_code</c>, <c>cursor</c>); never rename one.
/// </summary>
public sealed record UsageProvider(string Id, string DisplayName, string AccountName, string VendorName,
    SignInStyle SignIn, string Meters, string SignInSummary)
{
    public static readonly UsageProvider Codex = new("codex", "Codex", "ChatGPT", "OpenAI", SignInStyle.Loopback,
        "5-hour and weekly limits, reset credits", "ChatGPT sign-in");
    public static readonly UsageProvider ClaudeCode = new("claude_code", "Claude Code", "Claude", "Anthropic",
        SignInStyle.Loopback, "5-hour and weekly limits", "Claude sign-in");
    public static readonly UsageProvider Cursor = new("cursor", "Cursor", "Cursor", "Cursor", SignInStyle.BrowserPoll,
        "Included usage and extra spend", "Cursor sign-in");

    public static readonly UsageProvider Default = Codex;

    /// <summary>Every provider, in recommended order.</summary>
    public static IReadOnlyList<UsageProvider> All { get; } = [Codex, ClaudeCode, Cursor];

    public string SignInLabel => "Sign in with " + AccountName;

    public override string ToString() => DisplayName;

    /// <summary>Resolves a saved id, falling back to <see cref="Default"/>.</summary>
    public static UsageProvider Resolve(string? id) => Find(id) ?? Default;

    public static UsageProvider? Find(string? id)
    {
        var normalized = id?.Trim().ToLowerInvariant();
        return All.FirstOrDefault(p => p.Id == normalized);
    }

    /// <summary>
    /// Every provider in the saved order: saved ids first (duplicates and unknown ids skipped),
    /// then the rest in recommended order.
    /// </summary>
    public static List<UsageProvider> OrderFromIds(IEnumerable<string>? ids)
    {
        var order = new List<UsageProvider>();
        foreach (var id in ids ?? [])
        {
            if (Find(id) is { } provider && !order.Contains(provider)) order.Add(provider);
        }
        order.AddRange(All.Where(p => !order.Contains(p)));
        return order;
    }

    /// <summary>Enabled providers from saved ids; nothing valid saved means Codex plus anything signed in.</summary>
    public static List<UsageProvider> EnabledFromIds(IEnumerable<string>? ids, ICollection<UsageProvider> signedIn)
    {
        var set = (ids ?? []).Select(i => i.Trim().ToLowerInvariant()).ToHashSet();
        var enabled = All.Where(p => set.Contains(p.Id)).ToList();
        return enabled.Count > 0 ? enabled : All.Where(p => p == Default || signedIn.Contains(p)).ToList();
    }

    /// <summary><paramref name="enabled"/> with one provider switched; the last one can't be turned off.</summary>
    public static List<UsageProvider> WithEnabled(IReadOnlyCollection<UsageProvider> enabled, UsageProvider provider, bool on)
    {
        var result = All.Where(p => p == provider ? on : enabled.Contains(p)).ToList();
        return result.Count == 0 ? enabled.ToList() : result;
    }

    /// <summary>The selected provider if still enabled, otherwise the first enabled one.</summary>
    public static UsageProvider VisibleSelection(UsageProvider selected, IReadOnlyList<UsageProvider> enabled) =>
        enabled.Count == 0 || enabled.Contains(selected) ? selected : enabled[0];
}
