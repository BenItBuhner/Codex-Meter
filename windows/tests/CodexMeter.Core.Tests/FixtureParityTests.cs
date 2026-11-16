using System.Text.Json;
using CodexMeter.Core.Models;
using CodexMeter.Core.Updates;

namespace CodexMeter.Core.Tests;

/// <summary>The fixtures android/tests/PayloadBudgetSelfTest reads, read by the desktop core too.</summary>
public class FixtureParityTests
{
    private static string Android(string name) =>
        File.ReadAllText(Path.Combine(AppContext.BaseDirectory, "Fixtures", "android", name));

    [Fact]
    public void PlanValuesMatchTheSharedFixture()
    {
        using var doc = JsonDocument.Parse(Android("plan-pricing.json"));
        foreach (var (provider, lookup) in new (string, Func<string, PlanPricing?>)[]
                 { ("codex", PlanPricing.ForCodexPlan), ("claude", PlanPricing.ForClaudePlan) })
        {
            foreach (var plan in doc.RootElement.GetProperty(provider).EnumerateObject())
            {
                var pricing = lookup(plan.Name);
                var want = plan.Value;
                if (want.ValueKind == JsonValueKind.String && want.GetString() == "none")
                    Assert.True(pricing is null, $"{provider} {plan.Name} has no card");
                else if (want.ValueKind == JsonValueKind.String)
                    Assert.True(pricing is { HasValue: false }, $"{provider} {plan.Name} shows unknown");
                else
                    Assert.True(pricing is not null && Math.Abs(pricing.MonthlyValueUsd - want.GetDouble()) < 0.5,
                        $"{provider} {plan.Name}: want {want.GetDouble()}, got {pricing?.MonthlyValueUsd}");
            }
        }
    }

    [Fact]
    public void RealReleasePageParsesToTheNewestAlphaWithDesktopAssets()
    {
        var client = new UpdateClient("codex-meter-tests/1");
        var releases = ReleaseFeed.Parse(Android("github-releases.json"), ReleaseFeedTests.WinX64, client.IsTrustedReleaseUrl);
        Assert.Equal(30, releases.Count);
        Assert.Equal("2.9.0-alpha.29", releases.OrderByDescending(r => r.Version).First().Version.Normalized);
        Assert.All(releases, r => Assert.True(r.Notes.Length <= ReleaseFeed.MaxNotes + 1));
        Assert.Contains(releases, r => r.HasDownload);
    }
}
