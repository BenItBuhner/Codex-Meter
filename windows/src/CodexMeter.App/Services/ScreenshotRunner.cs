using System.Drawing;
using System.Drawing.Imaging;
using CodexMeter.Core.Services;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
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
            for (var i = 0; i < pages.Count; i++)
            {
                window.Navigate(pages[i]);
                await Task.Delay(1200);
                failures += await CaptureAsync($"{name}-{i + 1:00}-{pages[i]}.png") ? 0 : 1;
            }
            // Narrow window: the pane collapses and the meter cards stack.
            FitWindow(560, 820);
            window.Navigate(pages[0]);
            await Task.Delay(1200);
            failures += await CaptureAsync($"{name}-{pages.Count + 1:00}-compact.png") ? 0 : 1;
        }
        SaveTrayIcons();
        return failures == 0 ? 0 : 4;
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

    private bool CaptureScreen(string path)
    {
        if (NativeMethods.DwmGetWindowAttribute(window.Handle, NativeMethods.DwmwaExtendedFrameBounds, out var rect,
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

    private void SaveTrayIcons()
    {
        foreach (var (value, remaining) in new[] { (62, 62), (28, 28), (7, 7) })
        {
            using var icon = TrayIcon.Draw(value, remaining);
            using var bitmap = icon.ToBitmap();
            bitmap.Save(Path.Combine(directory, $"tray-{value}.png"), ImageFormat.Png);
        }
    }
}
