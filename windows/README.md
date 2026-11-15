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
- **Live usage in the notification area**, like the Android live notification: the icon is redrawn
  as a ring (or a number) for one service's tighter window, turning amber, then red, and showing the
  time until reset once a limit is used up. While ChatGPT, Codex, Claude, Cursor, SpaceXSI's Grok
  (Grok Bot) or another AI app is in front, it shows that service. Apps are recognized by process
  name, then by the executable's product and publisher (Anthropic, Anysphere, SpaceXAI). Hover for a tooltip with every service, click for a compact flyout
  with each one's meters, and right-click to open, refresh or quit. Settings → Live usage picks the
  service, "While using AI apps", the percentage shown (Auto, 5-hour or weekly) and the icon style.
  Windows toasts announce resets and low allowance.
- **In-app updates** (Settings → Updates, and "Check for updates" in the tray menu), like Android's:
  a Stable or Alpha channel, automatic checks (hourly to weekly) and a toast once per new version.
  A check asks GitHub for at most two small responses (`/releases/latest`, plus `?per_page=10` on
  alpha, falling back to 3), with hard size caps, so a long release list can't break it. The setup
  (or the portable zip) is downloaded, its SHA-256 checked against the release's
  `SHA256SUMS-windows.txt` (and its Authenticode signature, when it has one), then the app quits,
  the setup upgrades it silently and reopens it. A tampered or truncated download is deleted.
- **Priority**: the first page in the sidebar, like Android's: limits to watch (fast pace or 15% or
  less left), used-up limits and when they come back, what renews or expires soon, and new models
  from the makers you use.
- **Estimated value** for Codex, Claude (Pro, Max, Ultra priced as Claude plans) and Cursor (its
  included usage); other services get no card.
- **"Usage is back"** and surprise-refill notifications, as on Android.

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

Release tags build a setup .exe for each architecture from `installer/CodexMeter.iss` (Inno Setup,
per-user install, no admin prompt) and attach it to the GitHub release next to portable zips.

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

`.github/workflows/desktop-update-proof.yml` proves the updater on a clean Windows VM: it installs a
build stamped 2.9.0-alpha.27, serves a 2.9.0-alpha.28 setup from a loopback stand-in for GitHub's
API (`.github/update-proof/feed.py`, whose first download is tampered), and records the app finding
the update, refusing the bad download, installing the good one and reopening as the new version.
Nothing is tagged or published. Run the app against such a feed with
`CodexMeter.exe --update-feed http://127.0.0.1:8765/releases` (loopback addresses only).

## Not yet

- **Windows 11 Widgets board**: needs an MSIX-packaged app with a COM widget provider. The tray icon
  covers at-a-glance use for now.
- Usage history charts and the extra providers that only the Android app supports.
