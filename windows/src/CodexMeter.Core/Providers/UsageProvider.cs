namespace CodexMeter.Core.Providers;

/// <summary>How a provider's account is connected.</summary>
public enum SignInStyle
{
    /// <summary>Browser sign-in that returns to a local loopback port (Codex, Claude Code, OpenRouter).</summary>
    Loopback,
    /// <summary>Browser approval the app polls for (Cursor).</summary>
    BrowserPoll,
    /// <summary>The app shows a short code, the user approves it on the provider's site, the app polls.</summary>
    DeviceCode,
    /// <summary>The provider's site shows a token or code that the user pastes back.</summary>
    CodePaste,
    /// <summary>The user creates a key on the provider's site and pastes it.</summary>
    ApiKey,
}

/// <summary>
/// A coding-agent service whose limits Codex Meter shows. Ids are shared with the Android and
/// iOS apps (<c>codex</c>, <c>claude_code</c>, <c>cursor</c>, <c>copilot</c>, ...); never rename one.
/// The catalog fields (<see cref="Meters"/>, <see cref="SignIn"/>, <see cref="KeyUrl"/>, ...) mirror
/// the phone apps' <c>ProviderCatalog</c>.
/// </summary>
public sealed record UsageProvider(string Id, string DisplayName, string AccountName, string VendorName,
    SignInStyle SignIn, string Meters, string SignInSummary)
{
    /// <summary>Where the user creates a key; also offered as an alternative for device flows.</summary>
    public string KeyUrl { get; init; } = "";

    /// <summary>Whether the sign-in dialog also accepts a pasted API key.</summary>
    public bool AcceptsApiKey { get; init; }

    /// <summary>
    /// The sign-in flow is known, but it isn't yet confirmed that its token is accepted by the
    /// usage route. The Add account dialog and the sign-in dialog say so.
    /// </summary>
    public bool Inferred { get; init; }

    /// <summary>False when this build can't connect the provider (Antigravity needs a client secret).</summary>
    public bool IsAvailable { get; init; } = true;

    public static readonly UsageProvider Codex = new("codex", "Codex", "ChatGPT", "OpenAI", SignInStyle.Loopback,
        "5-hour and weekly limits, reset credits", "ChatGPT sign-in");
    public static readonly UsageProvider ClaudeCode = new("claude_code", "Claude Code", "Claude", "Anthropic",
        SignInStyle.Loopback, "5-hour and weekly limits", "Claude sign-in");
    public static readonly UsageProvider Cursor = new("cursor", "Cursor", "Cursor", "Cursor", SignInStyle.BrowserPoll,
        "Included usage and on-demand spend", "Cursor sign-in");

    // Added through Accounts → Add account; the same ids, sign-in flows and routes as Android and iOS.
    public static readonly UsageProvider Copilot = new("copilot", "Copilot", "GitHub", "GitHub", SignInStyle.DeviceCode,
        "Monthly premium requests and chat", "GitHub sign-in");
    public static readonly UsageProvider Kimi = new("kimi", "Kimi Code", "Kimi", "Moonshot AI", SignInStyle.DeviceCode,
        "5-hour and weekly limits", "Kimi sign-in") { KeyUrl = "https://www.kimi.com/code/console", AcceptsApiKey = true };
    public static readonly UsageProvider Factory = new("factory", "Factory", "Factory", "Factory", SignInStyle.DeviceCode,
        "5-hour, weekly and monthly limits", "Factory sign-in");
    public static readonly UsageProvider Grok = new("grok", "Grok", "xAI", "xAI", SignInStyle.DeviceCode,
        "SuperGrok credits for the billing period", "xAI sign-in") { Inferred = true };
    public static readonly UsageProvider Antigravity = new("antigravity", "Antigravity", "Google", "Google", SignInStyle.Loopback,
        "Gemini and Claude model limits, 5-hour and weekly", "Google sign-in") { IsAvailable = false };
    public static readonly UsageProvider Devin = new("devin", "Devin", "Devin", "Cognition", SignInStyle.CodePaste,
        "Daily and weekly quota", "Windsurf token") { Inferred = true };
    public static readonly UsageProvider Windsurf = new("windsurf", "Windsurf", "Windsurf", "Cognition", SignInStyle.CodePaste,
        "Daily and weekly quota", "Windsurf token") { Inferred = true };
    public static readonly UsageProvider Augment = new("augment", "Augment", "Augment", "Augment Code", SignInStyle.CodePaste,
        "Monthly credits", "Augment sign-in");
    public static readonly UsageProvider Cline = new("cline", "Cline", "Cline", "Cline", SignInStyle.DeviceCode,
        "5-hour, weekly and monthly limits", "Cline sign-in") { Inferred = true };
    public static readonly UsageProvider OpencodeGo = new("opencode_go", "OpenCode Go", "OpenCode", "OpenCode", SignInStyle.DeviceCode,
        "5-hour, weekly and monthly limits", "OpenCode sign-in") { KeyUrl = "https://opencode.ai/auth", AcceptsApiKey = true, Inferred = true };
    public static readonly UsageProvider MiniMax = new("minimax", "MiniMax", "MiniMax", "MiniMax", SignInStyle.DeviceCode,
        "Coding plan prompts per window", "MiniMax sign-in")
    {
        KeyUrl = "https://platform.minimax.io/user-center/basic-information/interface-key", AcceptsApiKey = true, Inferred = true,
    };
    public static readonly UsageProvider Zai = new("zai", "Z.ai", "Z.ai", "Z.ai", SignInStyle.ApiKey,
        "5-hour and weekly limits", "API key") { KeyUrl = "https://z.ai/manage-apikey/apikey-list", AcceptsApiKey = true };
    public static readonly UsageProvider Kiro = new("kiro", "Kiro", "AWS Builder ID", "AWS", SignInStyle.DeviceCode,
        "Monthly credits", "AWS Builder ID") { Inferred = true };
    public static readonly UsageProvider Amp = new("amp", "Amp", "Amp", "Amp", SignInStyle.ApiKey,
        "Free daily usage and credits", "Access token") { KeyUrl = "https://ampcode.com/settings", AcceptsApiKey = true, Inferred = true };
    public static readonly UsageProvider OpenRouter = new("openrouter", "OpenRouter", "OpenRouter", "OpenRouter", SignInStyle.Loopback,
        "Key limit and credit balance", "OpenRouter sign-in") { KeyUrl = "https://openrouter.ai/settings/keys", AcceptsApiKey = true };
    public static readonly UsageProvider Kilo = new("kilo", "Kilo Code", "Kilo", "Kilo", SignInStyle.DeviceCode,
        "Credit balance", "Kilo sign-in") { KeyUrl = "https://app.kilo.ai/profile", AcceptsApiKey = true };
    public static readonly UsageProvider Codebuff = new("codebuff", "Codebuff", "Codebuff", "Codebuff", SignInStyle.DeviceCode,
        "Subscription credits", "Codebuff sign-in") { Inferred = true };
    public static readonly UsageProvider Synthetic = new("synthetic", "Synthetic", "Synthetic", "Synthetic", SignInStyle.ApiKey,
        "5-hour and weekly limits", "API key") { KeyUrl = "https://synthetic.new/billing", AcceptsApiKey = true };
    public static readonly UsageProvider Chutes = new("chutes", "Chutes", "Chutes", "Chutes", SignInStyle.ApiKey,
        "Subscription quota", "API key") { KeyUrl = "https://chutes.ai/app/api", AcceptsApiKey = true, Inferred = true };
    public static readonly UsageProvider V0 = new("v0", "v0", "v0", "Vercel", SignInStyle.ApiKey,
        "Rate limits", "API key") { KeyUrl = "https://v0.app/chat/settings/keys", AcceptsApiKey = true, Inferred = true };
    public static readonly UsageProvider Warp = new("warp", "Warp", "Warp", "Warp", SignInStyle.ApiKey,
        "AI request limit", "API key") { KeyUrl = "https://app.warp.dev/settings/api-keys", AcceptsApiKey = true, Inferred = true };

    public static readonly UsageProvider Default = Codex;

    /// <summary>Every provider, in recommended order (the order the Add account dialog lists them).</summary>
    public static IReadOnlyList<UsageProvider> All { get; } =
    [
        Codex, ClaudeCode, Cursor, Copilot, Kimi, Factory, Grok, Antigravity, Devin, Windsurf, Augment, Cline,
        OpencodeGo, MiniMax, Zai, Kiro, Amp, OpenRouter, Kilo, Codebuff, Synthetic, Chutes, V0, Warp,
    ];

    /// <summary>Codex, Claude Code and Cursor: their own services, dashboards and sign-in flows.</summary>
    public static IReadOnlyList<UsageProvider> BuiltIns { get; } = [Codex, ClaudeCode, Cursor];

    public bool IsBuiltIn => this == Codex || this == ClaudeCode || this == Cursor;

    public string SignInLabel => SignIn switch
    {
        SignInStyle.ApiKey => "Add API key",
        _ => "Sign in with " + AccountName,
    };

    /// <summary>One line for Add account rows: "Monthly premium requests and chat · GitHub sign-in".</summary>
    public string PickerSummary => $"{Meters} · {SignInSummary}";

    public override string ToString() => DisplayName;

    // Records compare by value; a provider is identified by its id alone.
    public bool Equals(UsageProvider? other) => other is not null && other.Id == Id;

    public override int GetHashCode() => Id.GetHashCode(StringComparison.Ordinal);

    /// <summary>Resolves a saved id, falling back to <see cref="Default"/>.</summary>
    public static UsageProvider Resolve(string? id) => Find(id) ?? Default;

    public static UsageProvider? Find(string? id)
    {
        var normalized = id?.Trim().ToLowerInvariant();
        return All.FirstOrDefault(p => p.Id == normalized);
    }

    /// <summary>
    /// Providers the Add account dialog offers: everything not already connected that this build
    /// can sign in to, in recommended order, narrowed by <paramref name="query"/> (matched against
    /// the name, account, company, meters and sign-in kind).
    /// </summary>
    public static List<UsageProvider> PickerProviders(IEnumerable<UsageProvider> connected, string? query)
    {
        var skip = connected.ToHashSet();
        var needle = (query ?? "").Trim().ToLowerInvariant();
        return All.Where(p => p.IsAvailable && !skip.Contains(p) && (needle.Length == 0 || p.Matches(needle))).ToList();
    }

    private bool Matches(string needle) =>
        string.Join(' ', DisplayName, AccountName, VendorName, Meters, SignInSummary).ToLowerInvariant().Contains(needle);

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
