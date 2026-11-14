using System.Diagnostics;
using System.Security.Cryptography;
using CodexMeter.Core.Services;
using Microsoft.Win32;
using Microsoft.Windows.AppNotifications;
using Microsoft.Windows.AppNotifications.Builder;

namespace CodexMeter.App.Services;

/// <summary>Windows Data Protection (DPAPI), scoped to the signed-in Windows user.</summary>
public sealed class DpapiProtector : IDataProtector
{
    private static readonly byte[] Entropy = "CodexMeter.Windows.Tokens.v1"u8.ToArray();
    public byte[] Protect(byte[] data) => ProtectedData.Protect(data, Entropy, DataProtectionScope.CurrentUser);
    public byte[] Unprotect(byte[] data) => ProtectedData.Unprotect(data, Entropy, DataProtectionScope.CurrentUser);
}

/// <summary>The version this build was stamped with: "2.9.0-alpha.27" (no build metadata).</summary>
public static class AppVersion
{
    public static string Current { get; } = Read();

    private static string Read()
    {
        var version = typeof(AppVersion).Assembly
            .GetCustomAttributes(typeof(System.Reflection.AssemblyInformationalVersionAttribute), false)
            .OfType<System.Reflection.AssemblyInformationalVersionAttribute>().FirstOrDefault()?.InformationalVersion ?? "0.0.0";
        var plus = version.IndexOf('+');
        return plus >= 0 ? version[..plus] : version;
    }
}

public static class AppPaths
{
    public static string DataDirectory { get; } =
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "CodexMeter");
}

/// <summary>"Start with Windows" through the per-user Run key (no admin rights needed).</summary>
public static class StartupRegistration
{
    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string ValueName = "CodexMeter";

    public static bool IsEnabled
    {
        get
        {
            using var key = Registry.CurrentUser.OpenSubKey(RunKey);
            return key?.GetValue(ValueName) is string;
        }
    }

    public static void Set(bool enabled)
    {
        using var key = Registry.CurrentUser.CreateSubKey(RunKey);
        if (enabled)
            key.SetValue(ValueName, $"\"{Environment.ProcessPath}\" --minimized");
        else if (key.GetValue(ValueName) is not null)
            key.DeleteValue(ValueName);
    }
}

/// <summary>Windows toast notifications for resets and low allowance.</summary>
public sealed class Notifier
{
    private bool registered;

    /// <summary>A notification (or its button) was clicked; the argument is its action, like "update".</summary>
    public event Action<string?>? Invoked;

    public void Register()
    {
        try
        {
            AppNotificationManager.Default.NotificationInvoked += (_, args) =>
                Invoked?.Invoke(args.Arguments.TryGetValue("action", out var action) ? action : null);
            AppNotificationManager.Default.Register();
            registered = true;
        }
        catch (Exception error)
        {
            Debug.WriteLine("Notifications unavailable: " + error.Message);
        }
    }

    public void Show(UsageAlert alert, string? action = null)
    {
        if (!registered) return;
        try
        {
            var builder = new AppNotificationBuilder()
                .AddArgument("action", action ?? "open")
                .AddText(alert.Title)
                .AddText(alert.Body);
            if (action == "update") builder.AddButton(new AppNotificationButton("Update now").AddArgument("action", "update"));
            var notification = builder.BuildNotification();
            AppNotificationManager.Default.Show(notification);
        }
        catch (Exception error)
        {
            Debug.WriteLine("Notification failed: " + error.Message);
        }
    }

    public void Unregister()
    {
        if (!registered) return;
        try { AppNotificationManager.Default.Unregister(); } catch (Exception) { }
    }
}

public static class Browser
{
    public static Task OpenAsync(Uri url)
    {
        Process.Start(new ProcessStartInfo(url.AbsoluteUri) { UseShellExecute = true });
        return Task.CompletedTask;
    }
}
