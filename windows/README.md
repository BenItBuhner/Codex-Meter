# Codex Meter for Windows

A native Windows 11 app built with **WinUI 3** (Windows App SDK 1.8) and C#/.NET 10. It follows
Windows' own design: Mica (or Mica Alt / Acrylic) behind a custom title bar, a `NavigationView`
sidebar, Fluent cards and Settings cards, light and dark themes, and Segoe Fluent icons.

## What it does

- **A dashboard per provider**: Codex, Claude Code and Cursor, each with its own page in the sidebar,
  showing the same meters as the phone apps: 5-hour, weekly and monthly windows, Claude's per-model
  weekly limits and extra usage, Cursor's included usage, model pools and on-demand spend. Each meter
  has a ring, a bar, the reset time (countdown, date or both) and an optional pace estimate.
- **Codex banked resets**: see how many you have and when they expire, and use one (with a confirmation).
- **Accounts**: sign in to each provider in your browser (OAuth + PKCE with a localhost callback for
  Codex and Claude Code, the editor's browser login for Cursor) and sign out again.
- **Settings**: theme, window material, which providers appear and their order, used vs. remaining,
  reset format, 24-hour clock, pace, refresh interval, reset and low-allowance notifications,
  notification-area icon, keep running when closed, start with Windows.
- **Notification area**: an icon that shows your remaining percentage (turning amber, then red), a
  tooltip with every provider, and a menu to open, refresh or quit. Windows toasts announce resets
  and low allowance.

Credentials are encrypted with Windows Data Protection (DPAPI) for your Windows account and stored in
`%LOCALAPPDATA%\CodexMeter`, next to cached usage and settings. There is no relay server.

## Layout

| Path | What |
|------|------|
| `src/CodexMeter.Core` | Cross-platform library (`net10.0`): models, parsers, OAuth/PKCE, loopback server, provider clients, formatting, alerts, settings. A port of `ios/CodexMeterCore` and the Android Java core. |
| `src/CodexMeter.App` | The WinUI 3 app (unpackaged, self-contained, x64 and ARM64). |
| `tests/CodexMeter.Core.Tests` | xUnit tests for the core, reusing the iOS and Android JSON fixtures. Run anywhere, including Linux. |
| `tests/CodexMeter.App.UITests` | FlaUI (UI Automation) tests that drive the real app in demo mode. Windows only. |

### Why this stack

WinUI 3 is the current native UI framework for Windows 11, so Mica, the title bar, navigation and
controls come from Windows itself rather than an imitation. The usage logic can't be shared as code
with Swift or Java, so it is ported to a small C# library that has no Windows dependencies; that
lets its tests run on any OS and keeps the app layer thin. The tests use the same fixtures as the
iOS and Android tests, so the three implementations are checked against identical responses.

## Build and run

Requires Windows 10 1904x or later (Windows 11 for Mica) and the .NET 10 SDK.

```powershell
cd windows
dotnet test tests/CodexMeter.Core.Tests
dotnet publish src/CodexMeter.App -c Release -r win-x64 -p:Platform=x64 -o out/x64
out/x64/CodexMeter.exe
```

Command-line switches:

| Switch | Effect |
|--------|--------|
| `--demo` | Sample data for every provider, nothing saved, no network. |
| `--demo-signed-out` | Demo mode with no accounts connected. |
| `--page codex\|claude_code\|cursor\|accounts\|settings` | Open a page. |
| `--theme light\|dark\|system` | Override the theme. |
| `--minimized` | Start in the notification area (used by "Start with Windows"). |
| `--screenshots DIR` | Capture every page in light and dark into `DIR`, then exit. |

UI tests: build the app, then
`$env:CODEXMETER_EXE = "...\CodexMeter.exe"; dotnet test tests/CodexMeter.App.UITests`.

## CI

`.github/workflows/windows-ci.yml` runs the core tests on Linux and Windows, publishes x64 and ARM64
builds, captures screenshots, runs the UI tests, and uploads the zips and screenshots as artifacts.

## Not yet

- **Windows 11 Widgets board**: needs an MSIX-packaged app with a COM widget provider. The tray icon
  covers at-a-glance use for now.
- Usage history charts and the extra providers that only the Android app supports.
