using CodexMeter.Core.Services;

namespace CodexMeter.Core.Tests;

public class WhatsNewTests
{
    [Fact]
    public void ShowsOncePerReleaseToUpgradesOnly()
    {
        Assert.True(WhatsNew.ShouldShow(null, setUp: true));
        Assert.True(WhatsNew.ShouldShow("2.8", setUp: true));
        Assert.False(WhatsNew.ShouldShow(WhatsNew.Release, setUp: true));
        Assert.False(WhatsNew.ShouldShow(null, setUp: false));
        Assert.Contains(WhatsNew.Release, WhatsNew.Title);
    }

    [Fact]
    public void LaunchShowsItToUpgradesAndMarksNewInstallsSeen()
    {
        var upgrade = new AppSettings { OnboardingDone = true };
        var (show, kept) = WhatsNew.AtLaunch(upgrade, signedIn: 0);
        Assert.True(show);
        Assert.Null(kept.WhatsNewSeen);

        // Signed in before setup existed counts as set up too.
        Assert.True(WhatsNew.AtLaunch(new AppSettings(), signedIn: 1).Show);

        var (fresh, marked) = WhatsNew.AtLaunch(new AppSettings(), signedIn: 0);
        Assert.False(fresh);
        Assert.Equal(WhatsNew.Release, marked.WhatsNewSeen);

        var (again, _) = WhatsNew.AtLaunch(upgrade with { WhatsNewSeen = WhatsNew.Release }, signedIn: 2);
        Assert.False(again);
    }

    [Theory]
    [InlineData(WhatsNew.Platform.Windows)]
    [InlineData(WhatsNew.Platform.Linux)]
    public void EveryFeatureIsOneShortLine(WhatsNew.Platform platform)
    {
        var features = WhatsNew.Features(platform);
        Assert.True(features.Count >= 8);
        Assert.Equal(features.Count, features.Select(f => f.Id).Distinct().Count());
        foreach (var feature in features)
        {
            Assert.False(feature.Title.EndsWith('.'), feature.Title);
            Assert.EndsWith(".", feature.Body);
            Assert.True(feature.Body.Length <= 110, feature.Body);
        }
        Assert.Contains(features, f => f.Title == (platform == WhatsNew.Platform.Windows ? "Live usage in the notification area" : "Live usage in the tray"));
    }
}
