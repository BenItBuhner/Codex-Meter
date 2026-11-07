using System.Diagnostics;
using FlaUI.Core;
using FlaUI.Core.AutomationElements;
using FlaUI.Core.Conditions;
using FlaUI.Core.Tools;
using FlaUI.UIA3;

namespace CodexMeter.App.UITests;

/// <summary>Starts CodexMeter.exe in demo mode and finds elements by automation id.</summary>
public sealed class AppSession : IDisposable
{
    private static readonly TimeSpan Timeout = TimeSpan.FromSeconds(20);

    public AppSession(params string[] arguments)
    {
        var exe = Environment.GetEnvironmentVariable("CODEXMETER_EXE");
        if (string.IsNullOrEmpty(exe) || !File.Exists(exe))
            throw new InvalidOperationException("Set CODEXMETER_EXE to the built CodexMeter.exe.");
        Automation = new UIA3Automation();
        Application = FlaUI.Core.Application.Launch(new ProcessStartInfo(exe, ["--demo", .. arguments]));
        Window = Retry.WhileNull(() => Application.GetMainWindow(Automation, TimeSpan.FromSeconds(2)), Timeout,
            throwOnTimeout: true, timeoutMessage: "The main window never appeared.").Result!;
        Window.SetForeground();
    }

    public UIA3Automation Automation { get; }
    public FlaUI.Core.Application Application { get; }
    public Window Window { get; }

    private ConditionFactory Cf => Automation.ConditionFactory;

    public AutomationElement Find(string automationId) =>
        Retry.WhileNull(() => Window.FindFirstDescendant(Cf.ByAutomationId(automationId)), Timeout,
            throwOnTimeout: true, timeoutMessage: $"No element with automation id '{automationId}'.").Result!;

    public AutomationElement? TryFind(string automationId, TimeSpan? wait = null) =>
        Retry.WhileNull(() => Window.FindFirstDescendant(Cf.ByAutomationId(automationId)), wait ?? TimeSpan.FromSeconds(3)).Result;

    public AutomationElement FindByName(string name) =>
        Retry.WhileNull(() => Window.FindFirstDescendant(Cf.ByName(name)), Timeout,
            throwOnTimeout: true, timeoutMessage: $"No element named '{name}'.").Result!;

    public string TextOf(string automationId) =>
        Retry.WhileNull(() =>
        {
            var element = Window.FindFirstDescendant(Cf.ByAutomationId(automationId));
            var name = element?.Name;
            return string.IsNullOrEmpty(name) ? null : name;
        }, Timeout, throwOnTimeout: true, timeoutMessage: $"'{automationId}' has no text.").Result!;

    public void WaitUntil(Func<bool> condition, string message) =>
        Retry.WhileFalse(condition, Timeout, throwOnTimeout: true, timeoutMessage: message);

    public void Open(string navId)
    {
        var item = Find(navId);
        if (item.Patterns.SelectionItem.IsSupported) item.Patterns.SelectionItem.Pattern.Select();
        else item.Click();
    }

    public void Dispose()
    {
        try { Application.Close(); } catch { }
        try { if (!Application.HasExited) Application.Kill(); } catch { }
        Application.Dispose();
        Automation.Dispose();
    }
}
