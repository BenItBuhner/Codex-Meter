using System.Diagnostics;
using System.Globalization;

namespace CodexMeter.Core.Services;

/// <summary>
/// <c>--perf-budget FILE</c>, which the CI performance budgets launch the desktop apps with: the app
/// starts in demo mode, notes how long its window took to first draw (from process start), waits for
/// the dashboard to settle, then writes that time and its working set to FILE and quits.
/// </summary>
public static class PerfBudget
{
    /// <summary>How long the app runs after its first frame before its memory is read.</summary>
    public static readonly TimeSpan SettleTime = TimeSpan.FromSeconds(5);

    /// <summary>Milliseconds from this process's start to <paramref name="now"/> (default: now).</summary>
    public static double MillisecondsSinceProcessStart(DateTime? now = null)
    {
        using var process = Process.GetCurrentProcess();
        return ((now ?? DateTime.Now) - process.StartTime).TotalMilliseconds;
    }

    /// <summary>The report the budget scripts read: one <c>key=value</c> per line.</summary>
    public static string Report(double firstFrameMilliseconds, long workingSetBytes) =>
        string.Create(CultureInfo.InvariantCulture,
            $"first_frame_ms={Math.Round(firstFrameMilliseconds)}\nworking_set_mb={workingSetBytes / 1048576.0:F1}\n");

    /// <summary>Waits <see cref="SettleTime"/>, then writes the report with the working set at that moment.</summary>
    public static async Task WriteAfterSettleAsync(string path, double firstFrameMilliseconds)
    {
        await Task.Delay(SettleTime);
        using var process = Process.GetCurrentProcess();
        process.Refresh();
        await File.WriteAllTextAsync(path, Report(firstFrameMilliseconds, process.WorkingSet64));
    }
}
