using CodexMeter.Core.Services;

namespace CodexMeter.Linux.Services;

/// <summary>Desktop notifications for resets and low allowance, through GNotification (portal or freedesktop).</summary>
public sealed class Notifier(Gio.Application application, bool enabled)
{
    public void Show(UsageAlert alert)
    {
        if (!enabled) return;
        try
        {
            var notification = Gio.Notification.New(alert.Title);
            notification.SetBody(alert.Body);
            notification.SetDefaultAction("app.show");
            notification.SetPriority(alert.Kind == UsageAlertKind.Reset ? Gio.NotificationPriority.Normal : Gio.NotificationPriority.High);
            application.SendNotification(alert.Key, notification);
        }
        catch (Exception error)
        {
            AppLog.Write("Notification failed: " + error.Message);
        }
    }
}
