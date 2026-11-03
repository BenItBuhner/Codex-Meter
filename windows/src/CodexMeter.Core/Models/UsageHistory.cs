using System.Globalization;
using System.Text.Json;
using System.Text.Json.Serialization;
using CodexMeter.Core.Services;

namespace CodexMeter.Core.Models;

public enum UsageHistoryKind { FiveHour, Weekly, Monthly }

/// <summary>One locally observed allowance value. It contains no account or credential data.</summary>
public sealed record UsageSample
{
    [JsonConstructor]
    public UsageSample(DateTimeOffset observedAt, int usedPercent, DateTimeOffset resetAt, long windowSeconds)
    {
        ObservedAt = observedAt;
        UsedPercent = Math.Clamp(usedPercent, 0, 100);
        ResetAt = resetAt;
        WindowSeconds = Math.Max(0, windowSeconds);
    }

    public DateTimeOffset ObservedAt { get; init; }
    public int UsedPercent { get; init; }
    public DateTimeOffset ResetAt { get; init; }
    public long WindowSeconds { get; init; }

    [JsonIgnore]
    internal bool IsValid => ObservedAt.ToUnixTimeSeconds() > 0 && ResetAt > ObservedAt && WindowSeconds > 0;

    /// <summary>When this sample's window began.</summary>
    [JsonIgnore] public DateTimeOffset WindowStart => ResetAt.AddSeconds(-WindowSeconds);
}

/// <summary>
/// Bounded, local-only samples used by usage charts and trend-aware pace estimates. A port of
/// iOS's <c>UsageHistory</c>.
/// </summary>
public sealed record UsageHistory
{
    [JsonConstructor]
    public UsageHistory(UsageHistoryKind kind, IReadOnlyList<UsageSample>? samples)
    {
        Kind = kind;
        Samples = (samples ?? []).Where(s => s.IsValid).OrderBy(s => s.ObservedAt).TakeLast(MaximumSamples(kind)).ToList();
    }

    public UsageHistoryKind Kind { get; init; }
    public IReadOnlyList<UsageSample> Samples { get; init; }

    public static UsageHistory Empty(UsageHistoryKind kind) => new(kind, []);

    public UsageHistory Appending(UsageWindow window, DateTimeOffset observedAt)
    {
        if (observedAt.ToUnixTimeSeconds() <= 0 || window.WindowSeconds <= 0
            || window.EffectiveResetDate(observedAt) is not { } resetAt || resetAt <= observedAt)
            return this;
        var next = new UsageSample(observedAt, window.UsedPercent, resetAt, window.WindowSeconds);
        var updated = Samples.ToList();
        if (updated.Count > 0)
        {
            var last = updated[^1];
            if (observedAt <= last.ObservedAt) return this;
            var spacing = Kind switch
            {
                UsageHistoryKind.FiveHour => TimeSpan.FromMinutes(5),
                UsageHistoryKind.Weekly => TimeSpan.FromMinutes(30),
                _ => TimeSpan.FromHours(2),
            };
            if (SameWindow(last, next) && last.UsedPercent == next.UsedPercent && observedAt - last.ObservedAt < spacing)
            {
                updated[^1] = next;
                return new UsageHistory(Kind, updated);
            }
        }
        updated.Add(next);
        return new UsageHistory(Kind, updated);
    }

    /// <summary>Samples from the latest reset window, oldest first.</summary>
    [JsonIgnore]
    public IReadOnlyList<UsageSample> CurrentWindowSamples =>
        Samples.Count == 0 ? [] : Samples.Where(s => SameWindow(s, Samples[^1])).ToList();

    /// <summary>The most recent reset windows, oldest window first and oldest sample first.</summary>
    public List<List<UsageSample>> RecentWindows(int maximum)
    {
        var windows = new List<List<UsageSample>>();
        if (maximum <= 0 || Samples.Count == 0) return windows;
        var current = new List<UsageSample>();
        UsageSample? previous = null;
        foreach (var sample in Samples)
        {
            if (previous is not null && !SameWindow(previous, sample))
            {
                windows.Add(current);
                current = [];
            }
            current.Add(sample);
            previous = sample;
        }
        if (current.Count > 0) windows.Add(current);
        return windows.TakeLast(maximum).ToList();
    }

    [JsonIgnore]
    public int CompletedWindowCount => Math.Max(0, RecentWindows(int.MaxValue).Count - 1);

    /// <summary>Recent observed burn in percentage points per second.</summary>
    [JsonIgnore]
    public double ObservedBurnRate
    {
        get
        {
            var current = CurrentWindowSamples;
            if (current.Count < 2) return 0;
            var latest = current[^1];
            var minimumSpan = Kind switch
            {
                UsageHistoryKind.FiveHour => TimeSpan.FromMinutes(10),
                UsageHistoryKind.Weekly => TimeSpan.FromHours(2),
                _ => TimeSpan.FromHours(6),
            };
            var first = current.FirstOrDefault(s => latest.ObservedAt - s.ObservedAt >= minimumSpan && latest.UsedPercent - s.UsedPercent >= 2);
            if (first is null) return 0;
            var elapsed = (latest.ObservedAt - first.ObservedAt).TotalSeconds;
            return elapsed > 0 ? (latest.UsedPercent - first.UsedPercent) / elapsed : 0;
        }
    }

    private static int MaximumSamples(UsageHistoryKind kind) => kind switch
    {
        UsageHistoryKind.FiveHour => 288,
        UsageHistoryKind.Weekly => 336,
        _ => 372,
    };

    public static TimeSpan ResetTolerance(long windowSeconds) =>
        windowSeconds <= 0 ? TimeSpan.FromSeconds(60) : TimeSpan.FromSeconds(Math.Min(15 * 60, Math.Max(60, windowSeconds / 20.0)));

    public static bool SameWindow(UsageSample left, UsageSample right) =>
        left.ResetAt.ToUnixTimeSeconds() > 0 && right.ResetAt.ToUnixTimeSeconds() > 0 && left.WindowSeconds == right.WindowSeconds
        && (left.ResetAt - right.ResetAt).Duration() <= ResetTolerance(left.WindowSeconds);
}

/// <summary>Every recorded window kind for one provider.</summary>
public sealed record ProviderHistory
{
    public UsageHistory FiveHour { get; init; } = UsageHistory.Empty(UsageHistoryKind.FiveHour);
    public UsageHistory Weekly { get; init; } = UsageHistory.Empty(UsageHistoryKind.Weekly);
    public UsageHistory Monthly { get; init; } = UsageHistory.Empty(UsageHistoryKind.Monthly);

    public UsageHistory this[UsageHistoryKind kind] => kind switch
    {
        UsageHistoryKind.FiveHour => FiveHour,
        UsageHistoryKind.Weekly => Weekly,
        _ => Monthly,
    };

    [JsonIgnore] public bool IsEmpty => FiveHour.Samples.Count == 0 && Weekly.Samples.Count == 0 && Monthly.Samples.Count == 0;

    /// <summary>Kinds with at least one sample, shortest first.</summary>
    [JsonIgnore]
    public IReadOnlyList<UsageHistory> Recorded => new[] { FiveHour, Weekly, Monthly }.Where(h => h.Samples.Count > 0).ToList();

    /// <summary>Adds the snapshot's 5-hour, weekly and monthly windows.</summary>
    public ProviderHistory Recording(UsageSnapshot snapshot)
    {
        var at = snapshot.FetchedAt;
        return this with
        {
            FiveHour = snapshot.FiveHour is { } five ? FiveHour.Appending(five, at) : FiveHour,
            Weekly = snapshot.Weekly is { } week ? Weekly.Appending(week, at) : Weekly,
            Monthly = snapshot.Monthly is { } month ? Monthly.Appending(month, at) : Monthly,
        };
    }
}

/// <summary>Usage history per provider in the app's data folder (<c>history-{id}.json</c>).</summary>
public sealed class UsageHistoryStore(string? directory)
{
    private readonly Dictionary<string, ProviderHistory> memory = new();
    private readonly SemaphoreSlim gate = new(1, 1);

    private string? PathFor(string id) => directory is null ? null : Path.Combine(directory, $"history-{id}.json");

    public async Task<ProviderHistory> LoadAsync(string providerId)
    {
        await gate.WaitAsync().ConfigureAwait(false);
        try { return await LoadLockedAsync(providerId).ConfigureAwait(false); }
        finally { gate.Release(); }
    }

    private async Task<ProviderHistory> LoadLockedAsync(string providerId)
    {
        if (memory.TryGetValue(providerId, out var cached)) return cached;
        var history = new ProviderHistory();
        if (PathFor(providerId) is { } path && File.Exists(path))
        {
            try
            {
                history = JsonSerializer.Deserialize<ProviderHistory>(await File.ReadAllBytesAsync(path).ConfigureAwait(false), Storage.Options)
                    ?? history;
            }
            catch (JsonException) { /* A damaged file starts over. */ }
        }
        memory[providerId] = history;
        return history;
    }

    public async Task<ProviderHistory> RecordAsync(string providerId, UsageSnapshot snapshot) =>
        await ChangeAsync(providerId, h => h.Recording(snapshot)).ConfigureAwait(false);

    public async Task<ProviderHistory> SetAsync(string providerId, ProviderHistory history) =>
        await ChangeAsync(providerId, _ => history).ConfigureAwait(false);

    public Task ClearAsync(string providerId) => ChangeAsync(providerId, _ => new ProviderHistory());

    private async Task<ProviderHistory> ChangeAsync(string providerId, Func<ProviderHistory, ProviderHistory> change)
    {
        await gate.WaitAsync().ConfigureAwait(false);
        try
        {
            var current = await LoadLockedAsync(providerId).ConfigureAwait(false);
            var updated = change(current);
            if (updated == current) return current;
            memory[providerId] = updated;
            if (PathFor(providerId) is { } path)
            {
                Directory.CreateDirectory(directory!);
                if (updated.IsEmpty) File.Delete(path);
                else await Storage.WriteAtomicAsync(path, JsonSerializer.SerializeToUtf8Bytes(updated, Storage.Options)).ConfigureAwait(false);
            }
            return updated;
        }
        finally { gate.Release(); }
    }
}

/// <summary>
/// Pure aggregation over recorded samples: per-window statistics, cross-window typical pace,
/// and interpolation for chart hover. A port of iOS's <c>UsageStats</c>.
/// </summary>
public static class UsageStats
{
    private static readonly TimeSpan MinimumRateSpacing = TimeSpan.FromSeconds(60);

    public sealed record WindowStats(DateTimeOffset WindowStart, DateTimeOffset ResetAt, DateTimeOffset FirstSampleAt,
        DateTimeOffset LastSampleAt, int FirstPercent, int FinalPercent, int SampleCount, double AverageBurnPercentPerHour,
        double PeakBurnPercentPerHour, bool Exhausted, bool Complete);

    public static WindowStats? Window(IReadOnlyList<UsageSample> samples, bool complete)
    {
        if (samples.Count == 0) return null;
        var first = samples[0];
        var last = samples[^1];
        var span = last.ObservedAt - first.ObservedAt;
        var burned = last.UsedPercent - first.UsedPercent;
        var average = span > TimeSpan.Zero && burned > 0 ? burned / span.TotalHours : 0;
        var peak = 0.0;
        for (var index = 1; index < samples.Count; index++)
        {
            var gap = samples[index].ObservedAt - samples[index - 1].ObservedAt;
            var delta = samples[index].UsedPercent - samples[index - 1].UsedPercent;
            if (gap < MinimumRateSpacing || delta <= 0) continue;
            peak = Math.Max(peak, delta / gap.TotalHours);
        }
        return new WindowStats(last.WindowStart, last.ResetAt, first.ObservedAt, last.ObservedAt, first.UsedPercent,
            last.UsedPercent, samples.Count, average, peak, last.UsedPercent >= 100, complete);
    }

    /// <summary>Per-window statistics, oldest to newest; the final entry is the current window.</summary>
    public static List<WindowStats> Breakdown(UsageHistory? history, int maximumWindows)
    {
        if (history is null) return [];
        var windows = history.RecentWindows(maximumWindows);
        return windows.Select((samples, index) => Window(samples, index < windows.Count - 1)).OfType<WindowStats>().ToList();
    }

    /// <summary>Interpolated used percent at <paramref name="moment"/>, or null outside the sample span.</summary>
    public static double? UsedPercentAt(DateTimeOffset moment, IReadOnlyList<UsageSample> samples)
    {
        if (samples.Count == 0 || moment < samples[0].ObservedAt || moment > samples[^1].ObservedAt) return null;
        if (samples.Count == 1) return samples[0].UsedPercent;
        for (var index = 1; index < samples.Count; index++)
        {
            var left = samples[index - 1];
            var right = samples[index];
            if (moment > right.ObservedAt) continue;
            var span = (right.ObservedAt - left.ObservedAt).TotalSeconds;
            if (span <= 0) return right.UsedPercent;
            return left.UsedPercent + (right.UsedPercent - left.UsedPercent) * ((moment - left.ObservedAt).TotalSeconds / span);
        }
        return samples[^1].UsedPercent;
    }

    /// <summary>Typical used percent at an elapsed fraction (0–1) of the window, averaged over completed windows.</summary>
    public static double? TypicalUsedPercent(UsageHistory? history, double fraction)
    {
        if (history is null) return null;
        var clamped = Math.Clamp(fraction, 0, 1);
        var windows = history.RecentWindows(int.MaxValue);
        if (windows.Count <= 1) return null;
        double total = 0;
        var counted = 0;
        foreach (var window in windows.Take(windows.Count - 1))
        {
            if (window.Count < 2) continue;
            var reference = window[^1];
            var moment = reference.WindowStart.AddSeconds(clamped * reference.WindowSeconds);
            total += UsedPercentAt(moment, window) ?? (moment < window[0].ObservedAt ? window[0].UsedPercent : reference.UsedPercent);
            counted++;
        }
        return counted == 0 ? null : total / counted;
    }

    /// <summary>Average final percent across completed windows.</summary>
    public static double? AverageFinalPercent(UsageHistory? history)
    {
        if (history is null) return null;
        var windows = history.RecentWindows(int.MaxValue);
        var completed = windows.Take(Math.Max(0, windows.Count - 1)).Select(w => w[^1].UsedPercent).ToList();
        return completed.Count == 0 ? null : completed.Average();
    }

    public static double PeakBurnPercentPerHour(UsageHistory? history) =>
        Breakdown(history, int.MaxValue).Select(s => s.PeakBurnPercentPerHour).DefaultIfEmpty(0).Max();

    public static string FormatRate(double percentPerHour) => percentPerHour >= 10
        ? $"{Math.Round(percentPerHour, MidpointRounding.AwayFromZero).ToString(CultureInfo.InvariantCulture)}%/h"
        : percentPerHour.ToString("0.0", CultureInfo.InvariantCulture) + "%/h";

    /// <summary>
    /// When the window runs out at the observed burn rate, or null when it won't before reset
    /// (or there's no trend yet).
    /// </summary>
    public static DateTimeOffset? ProjectedExhaustion(UsageHistory history, DateTimeOffset now)
    {
        var rate = history.ObservedBurnRate;
        var current = history.CurrentWindowSamples;
        if (rate <= 0 || current.Count == 0) return null;
        var latest = current[^1];
        if (latest.UsedPercent >= 100) return latest.ObservedAt;
        var at = latest.ObservedAt.AddSeconds((100 - latest.UsedPercent) / rate);
        return at < latest.ResetAt && at > now ? at : null;
    }
}
