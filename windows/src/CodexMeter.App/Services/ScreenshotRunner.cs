using System.Drawing;
using System.Drawing.Imaging;
using CodexMeter.App.Views;
using CodexMeter.Core.Formatting;
using CodexMeter.Core.Models;
using CodexMeter.Core.Providers;
using CodexMeter.Core.Services;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Imaging;
using Windows.Graphics.Imaging;
using Windows.Storage.Streams;

namespace CodexMeter.App.Services;

/// <summary>
/// <c>--screenshots DIR</c>: walks every page in light and dark with demo data, saves a PNG of the
/// real window (title bar and Mica included) for each, then exits. Used by CI to show the app.
/// </summary>
public sealed class ScreenshotRunner(MainWindow window, AppState state, string directory)
{
    public async Task<int> RunAsync()
    {
        Directory.CreateDirectory(directory);
        await Task.Delay(1500);
        var pages = state.EnabledProviders.Select(p => p.Id).Concat(["accounts", "settings"]).ToList();
        var failures = 0;
        foreach (var theme in new[] { AppTheme.Light, AppTheme.Dark })
        {
            state.Settings.Update(s => s with { Theme = theme });
            FitWindow(1180, 820);
            var name = theme.ToString().ToLowerInvariant();
            var shot = 0;
            string File(string label) => $"{name}-{++shot:00}-{label}.png";
            foreach (var page in pages)
            {
                window.Navigate(page);
                await Task.Delay(1200);
                var expected = page switch { "settings" => "SettingsPage", "accounts" => "AccountsPage", _ => "DashboardPage" };
                if (window.CurrentPageType?.Name != expected)
                {
                    App.Log($"Expected {expected} for {page} but the window shows {window.CurrentPageType?.Name ?? "nothing"}.");
                    failures++;
                }
                failures += await CaptureAsync(File(page)) ? 0 : 1;
            }

            // The title bar with the pointer over Close, so the caption buttons can be checked.
            failures += await CaptureCaptionHoverAsync(File("caption-hover"));

            // The usage history chart, scrolled into view.
            window.Navigate(pages[0]);
            await Task.Delay(1000);
            if (window.CurrentScrollViewer is { } scroller && Find(scroller, "HistoryCard") is FrameworkElement chart)
            {
                var top = chart.TransformToVisual((UIElement)scroller.Content).TransformPoint(new Windows.Foundation.Point(0, 0)).Y;
                scroller.ChangeView(null, Math.Max(0, top - 12), null, true);
                await Task.Delay(800);
                failures += await CaptureAsync(File("history")) ? 0 : 1;
                scroller.ChangeView(null, 0, null, true);
            }

            // Dialogs: Add account, Edit dashboard and a device-code sign-in.
            failures += await CaptureDialogAsync(File("add-account"), opened => window.AddAccountAsync(opened));
            failures += await CaptureDialogAsync(File("edit-dashboard"), opened => window.EditDashboardAsync(opened));
            if (state.Entries.FirstOrDefault(e => !e.SignedIn && e.Provider.SignIn == SignInStyle.DeviceCode)?.Provider is { } device
                && window.Content.XamlRoot is { } xamlRoot)
            {
                var signIn = SignInFlow.RunAsync(device, xamlRoot);
                await Task.Delay(1500);
                failures += await CaptureAsync(File("sign-in-code")) ? 0 : 1;
                CloseDialogs();
                await signIn;
            }

            // Narrow window: the pane collapses behind the title bar's menu button and cards stack.
            FitWindow(560, 820);
            window.Navigate(pages[0]);
            await Task.Delay(1200);
            failures += await CaptureAsync(File("compact")) ? 0 : 1;
            failures += await CaptureFlyoutAsync(File("tray-flyout"));
        }
        SaveTrayIcons();
        return failures == 0 ? 0 : 4;
    }

    private async Task<int> CaptureCaptionHoverAsync(string file)
    {
        if (NativeMethods.DwmGetWindowAttribute(window.Handle, NativeMethods.DwmwaExtendedFrameBounds, out var rect,
                System.Runtime.InteropServices.Marshal.SizeOf<NativeMethods.Rect>()) != 0) return 1;
        var scale = NativeMethods.GetDpiForWindow(window.Handle) / 96.0;
        NativeMethods.SetCursorPos(rect.Right - (int)(23 * scale), rect.Top + (int)(16 * scale));
        await Task.Delay(800);
        var ok = await CaptureAsync(file);
        NativeMethods.SetCursorPos(rect.Left + (rect.Right - rect.Left) / 2, rect.Bottom + (int)(40 * scale));
        await Task.Delay(400);
        return ok ? 0 : 1;
    }

    private async Task<int> CaptureDialogAsync(string file, Func<Action<ContentDialog>, Task> show)
    {
        var opened = new TaskCompletionSource<ContentDialog>();
        var task = show(dialog => opened.TrySetResult(dialog));
        var first = await Task.WhenAny(opened.Task, Task.Delay(5000));
        if (first != opened.Task)
        {
            App.Log($"No dialog opened for {file}.");
            return 1;
        }
        await Task.Delay(800);
        var ok = await CaptureAsync(file);
        opened.Task.Result.Hide();
        await task;
        return ok ? 0 : 1;
    }

    private void CloseDialogs()
    {
        foreach (var popup in VisualTreeHelper.GetOpenPopupsForXamlRoot(window.Content.XamlRoot))
            if (popup.Child is ContentDialog dialog) dialog.Hide();
    }

    private static DependencyObject? Find(DependencyObject parent, string automationId)
    {
        for (var i = 0; i < VisualTreeHelper.GetChildrenCount(parent); i++)
        {
            var child = VisualTreeHelper.GetChild(parent, i);
            if (AutomationProperties.GetAutomationId(child) == automationId) return child;
            if (Find(child, automationId) is { } found) return found;
        }
        return null;
    }

    private void FitWindow(int width, int height)
    {
        var area = DisplayArea.GetFromWindowId(window.AppWindow.Id, DisplayAreaFallback.Primary).WorkArea;
        var scale = NativeMethods.GetDpiForWindow(window.Handle) / 96.0;
        var w = Math.Min((int)(width * scale), area.Width - 16);
        var h = Math.Min((int)(height * scale), area.Height - 16);
        window.AppWindow.MoveAndResize(new Windows.Graphics.RectInt32(area.X + (area.Width - w) / 2, area.Y + (area.Height - h) / 2, w, h));
        window.ShowAndActivate();
    }

    private async Task<bool> CaptureAsync(string file)
    {
        var path = Path.Combine(directory, file);
        try
        {
            if (CaptureScreen(path)) return true;
        }
        catch (Exception error)
        {
            App.Log($"Screen capture of {file} failed: {error.Message}");
        }
        // Fallback when there is no visible desktop (e.g. a locked session): render the XAML tree.
        try
        {
            await RenderXamlAsync(path);
            return true;
        }
        catch (Exception error)
        {
            App.Log($"XAML capture of {file} failed: {error.Message}");
            return false;
        }
    }

    private bool CaptureScreen(string path) => CaptureScreen(path, window.Handle);

    private static bool CaptureScreen(string path, IntPtr handle)
    {
        if (NativeMethods.DwmGetWindowAttribute(handle, NativeMethods.DwmwaExtendedFrameBounds, out var rect,
                System.Runtime.InteropServices.Marshal.SizeOf<NativeMethods.Rect>()) != 0) return false;
        var width = rect.Right - rect.Left;
        var height = rect.Bottom - rect.Top;
        if (width <= 0 || height <= 0) return false;
        using var bitmap = new Bitmap(width, height, PixelFormat.Format32bppArgb);
        using (var g = Graphics.FromImage(bitmap))
            g.CopyFromScreen(rect.Left, rect.Top, 0, 0, new Size(width, height));
        if (IsBlank(bitmap)) return false;
        bitmap.Save(path, ImageFormat.Png);
        return true;
    }

    private static bool IsBlank(Bitmap bitmap)
    {
        var first = bitmap.GetPixel(bitmap.Width / 2, bitmap.Height / 2);
        for (var x = 0; x < bitmap.Width; x += Math.Max(1, bitmap.Width / 24))
            for (var y = 0; y < bitmap.Height; y += Math.Max(1, bitmap.Height / 24))
                if (bitmap.GetPixel(x, y) != first) return false;
        return true;
    }

    private async Task RenderXamlAsync(string path)
    {
        var root = (UIElement)window.Content;
        var target = new RenderTargetBitmap();
        await target.RenderAsync(root);
        var pixels = await target.GetPixelsAsync();
        using var stream = new InMemoryRandomAccessStream();
        var encoder = await BitmapEncoder.CreateAsync(BitmapEncoder.PngEncoderId, stream);
        var dpi = NativeMethods.GetDpiForWindow(window.Handle);
        encoder.SetPixelData(BitmapPixelFormat.Bgra8, BitmapAlphaMode.Premultiplied, (uint)target.PixelWidth,
            (uint)target.PixelHeight, dpi, dpi, System.Runtime.InteropServices.WindowsRuntime.WindowsRuntimeBufferExtensions.ToArray(pixels));
        await encoder.FlushAsync();
        stream.Seek(0);
        await using var file = File.Create(path);
        await stream.AsStreamForRead().CopyToAsync(file);
    }

    /// <summary>The tray flyout, opened above the notification area as a click would.</summary>
    private async Task<int> CaptureFlyoutAsync(string file)
    {
        var flyout = new TrayFlyout(state, _ => { }, () => { }) { LiveProvider = state.EnabledProviders.FirstOrDefault() };
        try
        {
            var area = DisplayArea.Primary.WorkArea;
            flyout.Show(new Windows.Graphics.PointInt32(area.X + area.Width - 40, area.Y + area.Height - 4));
            await Task.Delay(1200);
            var ok = CaptureScreen(Path.Combine(directory, file), flyout.Handle);
            if (!ok) App.Log($"Screen capture of {file} failed.");
            return ok ? 0 : 1;
        }
        finally
        {
            flyout.Dispose();
        }
    }

    /// <summary>
    /// A sheet of the live tray icon: ring and number styles for a healthy, low, critical and used-up
    /// window, at 16, 24 and 32px on a dark and a light taskbar, then the same sheet at 4x.
    /// </summary>
    private void SaveTrayIcons()
    {
        var now = DateTimeOffset.UtcNow;
        UsageWindow Sample(int used, double hours) => new(used, 18_000, resetAt: now.AddHours(hours));
        var samples = new[] { Sample(38, 2), Sample(72, 3), Sample(93, 1), Sample(100, 2.4) }
            .Select(w => new LiveReading(UsageProvider.Codex, w, "5-hour", now)).ToArray();
        int[] sizes = [16, 24, 32];
        const int cell = 40;
        var columns = samples.Length * sizes.Length;
        using var sheet = new Bitmap(columns * cell, 4 * cell);
        using (var g = Graphics.FromImage(sheet))
        {
            var row = 0;
            foreach (var dark in new[] { true, false })
            {
                foreach (var style in new[] { LiveIconStyle.Ring, LiveIconStyle.Number })
                {
                    using (var background = new SolidBrush(dark ? Color.FromArgb(0x20, 0x20, 0x20) : Color.FromArgb(0xEE, 0xEE, 0xEE)))
                        g.FillRectangle(background, 0, row * cell, columns * cell, cell);
                    var column = 0;
                    foreach (var size in sizes)
                    {
                        foreach (var sample in samples)
                        {
                            using var icon = TrayIcon.Draw(sample, style, UsageDisplayMode.Remaining, size, dark, now);
                            g.DrawImageUnscaled(icon, column * cell + (cell - size) / 2, row * cell + (cell - size) / 2);
                            column++;
                        }
                    }
                    row++;
                }
            }
        }
        sheet.Save(Path.Combine(directory, "tray-icons.png"), ImageFormat.Png);
        using var large = new Bitmap(sheet.Width * 4, sheet.Height * 4);
        using (var g = Graphics.FromImage(large))
        {
            g.InterpolationMode = System.Drawing.Drawing2D.InterpolationMode.NearestNeighbor;
            g.PixelOffsetMode = System.Drawing.Drawing2D.PixelOffsetMode.Half;
            g.DrawImage(sheet, 0, 0, large.Width, large.Height);
        }
        large.Save(Path.Combine(directory, "tray-icons-4x.png"), ImageFormat.Png);
    }
}
