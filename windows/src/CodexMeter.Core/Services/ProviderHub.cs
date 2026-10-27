using CodexMeter.Core.Models;
using CodexMeter.Core.Providers;

namespace CodexMeter.Core.Services;

/// <summary>The app's set of provider services, live or demo.</summary>
public sealed class ProviderHub
{
    private readonly Dictionary<string, ProviderService> services;

    private ProviderHub(Dictionary<string, ProviderService> services, bool demo)
    {
        this.services = services;
        IsDemo = demo;
    }

    public bool IsDemo { get; }

    public static ProviderHub Live(ProviderContext context) => new(new Dictionary<string, ProviderService>
    {
        [UsageProvider.Codex.Id] = new CodexService(context),
        [UsageProvider.ClaudeCode.Id] = new ClaudeCodeService(context),
        [UsageProvider.Cursor.Id] = new CursorService(context),
    }, false);

    /// <summary>Demo services with every provider already signed in (optionally all but some).</summary>
    public static async Task<ProviderHub> DemoAsync(ProviderContext context, IEnumerable<string>? signedOut = null)
    {
        var skip = (signedOut ?? []).ToHashSet();
        var map = UsageProvider.All.ToDictionary(p => p.Id, p => (ProviderService)new DemoProviderService(p, context));
        foreach (var provider in UsageProvider.All.Where(p => !skip.Contains(p.Id)))
            await context.Tokens.SaveAsync(provider.Id, DemoData.Account(provider, context.Now())).ConfigureAwait(false);
        return new ProviderHub(map, true);
    }

    public ProviderService this[UsageProvider provider] => services[provider.Id];

    public IEnumerable<ProviderService> All => UsageProvider.All.Select(p => services[p.Id]);

    public async Task<HashSet<UsageProvider>> SignedInAsync()
    {
        var result = new HashSet<UsageProvider>();
        foreach (var service in All)
        {
            if (await service.AccountAsync().ConfigureAwait(false) is { IsUsable: true }) result.Add(service.Provider);
        }
        return result;
    }

    /// <summary>Redeems a Codex reset credit on whichever service is active.</summary>
    public Task<ResetConsumeResult> ConsumeResetAsync(string? creditId) => this[UsageProvider.Codex] switch
    {
        CodexService live => live.ConsumeResetAsync(creditId),
        DemoProviderService demo => demo.ConsumeResetAsync(creditId),
        _ => Task.FromResult(new ResetConsumeResult(ResetConsumeOutcome.Unknown, 0)),
    };
}
