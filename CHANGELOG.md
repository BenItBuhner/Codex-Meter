# Changelog

## Unreleased

### Fixed

- **Liquid Glass** is back to how it looked and ran in 2.9.0-alpha.17. The iOS 26 and iOS 27 edge changes in 2.9.0-alpha.18 looked wrong and made scrolling and refreshes slow, with occasional freezes. Android.
- Refreshes no longer wait behind background lookups (service status, model catalogs and model speed), which run on their own thread now. The dashboard no longer re-reads the whole saved model catalog on every redraw, which slowed scrolling and refreshes. Android.

## 2.9.0-alpha.18 — 2026-11-08

### Added

- A **Material 3 Expressive** design in Settings › Appearance › App design, alongside One UI 8.5, One UI 9 and both Liquid Glass looks. Its dashboard uses Google's Material Components Expressive views: a large header, rounded cards with Expressive progress bars, a loading indicator while refreshing, and a floating toolbar of provider tabs. It shows the same cards as the other designs. Material You and light, dark or system theme work with it as they do elsewhere. Settings opens in the same Material style. Android.
- New installs start on the design that suits the phone: Material 3 Expressive on Pixels and other Google phones, and One UI 9 on Samsung phones that report One UI 9 or later (the app reads Samsung's One UI version, not the Android version). Other phones start on One UI 8.5 as before. Onboarding says which design it picked. This only happens once, on a new install with no saved design; if you already use the app, your design doesn't change. Android.
- **Priority view**: when something needs attention, a card at the top of the dashboard lists it across your signed-in services, in a fixed order: official incidents, then performance warnings, then limits that are used up or will run out before they reset, then your other meters and a tip. Everything in it, every number and every incident, comes from what the app already knows on your phone. You can add your name for the greeting; it stays on your phone. Settings › Refresh & dashboard › Priority. Android.
- An optional **Written summary** for the Priority card, off unless you turn it on. It asks an OpenCode free model, about once or twice a day, to word the summary line and tip from the card types, service names and states only. It never sends your name, prompts, code, usage numbers or sign-ins, and the app checks the reply before showing it. If no free model answers, you see the summary written on your phone; the app never switches to a paid model. OpenCode currently keeps its free models for its own app, so for now you'll usually see the on-phone summary. Android.
- Liquid Glass now comes in two looks in Settings › Appearance › App design: **Liquid Glass (iOS 26-inspired)**, the design as it has been, and **Liquid Glass (iOS 27-inspired)**, with more diffused glass, darker edges, brighter highlights, an even backdrop behind the top bar once content scrolls under it, and a **Glass tint** slider from ultra clear to fully tinted. Both looks share the same screens. If you already use Liquid Glass you stay on the iOS 26-inspired look; nothing switches you. Both are inspired designs drawn on Android, not Apple's own, and not pixel-for-pixel copies. Android.
- Liquid Glass has **Reduce transparency** and **Increase contrast** switches under Settings › Appearance, in both looks. Increase contrast starts on when Android's own contrast setting is high. Android.
- **Model speed reference** on the Codex, Claude Code and Grok tabs: pick a model and see its public throughput, latency and uptime on OpenRouter over the last 30 minutes, refreshed about every 45 minutes while the app is open. Figures OpenRouter hasn't measured read "Insufficient data". These are OpenRouter's numbers for the model, not your subscription's speed: the app sends no test prompts and has no timing of your own requests. Hide the card from its model picker or Settings › Refresh & dashboard › Model speed reference. Android.
- Service status on the dashboard. When the official status page reports a problem with a component the provider depends on, such as Claude Code (not the Claude API) or Grok Bot, a card above the meters names that component and the incident, and tapping it opens the status page. It uses OpenAI's, Anthropic's and Cursor's own status feeds and SpaceXSI's incident feed for Grok, checked about every 5 minutes while the app is open and never in the background. If a feed can't be read, the app treats the status as unknown and doesn't call it an outage. Antigravity has no official status feed, so it shows none. Android.
- The Windows tray now follows SpaceXSI's Grok desktop app (Grok Bot) while it is in front, as it already did for ChatGPT or Codex, Claude and Cursor. It recognizes the app by its process name, written with or without a space, and otherwise by the app's product or publisher name (Anthropic, Anysphere, SpaceXAI or xAI). Unrelated apps with similar names, such as "Claudette" or "Precursor", don't match. Windows.
- **New models** in the Priority view: a grouped card after your meters lists models newly added to public catalogs for the providers you have connected. OpenAI models show for Codex, Anthropic models for Claude Code, xAI models for Grok, MiniMax, Moonshot and Z.ai models for MiniMax, Kimi and Z.ai, and OpenRouter's whole catalog only if OpenRouter is connected. OpenCode Go, Kilo, Chutes and Synthetic show their own public catalogs when connected. Cursor, Antigravity and the other services add nothing. Each entry reads like "New on OpenRouter: GPT-6", with the note that newly listed isn't necessarily newly released and that your subscription's access isn't verified. Dismiss clears the card. The catalogs are fetched without any of your keys. The app checks about every 30 minutes while you're using it, hourly otherwise, every two hours overnight, and less often after days away. It never checks just because usage refreshes run more often. The first catalog the app sees is taken as the starting point and announces nothing. A model must be seen twice before it counts. Renamed aliases, free or batch variants, price changes and long-listed models are not announced, and a failed check never reads as models being removed. Android.
- An optional **New models** notification under Settings › Notifications, off by default. It sends one grouped, deduplicated digest using your silent or ordinary delivery style, and never the alarm sound. Turning it on doesn't replay models you already missed. The Refresh & dashboard screen explains how the check works. Android.

### Changed

- Claude plan badges use Anthropic's new names: Max 5x is now **Max** and Max 20x is now **Ultra**. The name comes from your Claude profile, so future renames show up without an update, and badges saved before the change are renamed too. All apps.
- The two **Liquid Glass** looks now differ the way iOS does. iOS 26 sets Glassé's iOS 26 look: a soft blur that fades out under the top bar and behind the tab bar. iOS 27 sets Glassé's iOS 27 look: a solid blurred band with a hairline under the top bar once you scroll, and the same band behind the tab bar only while there is more content below it. Its glass also has a more defined edge with less drop shadow, and lighter glass in dark mode. The Clear and Tinted transparency choices now change the glass visibly. Before this, both looks used Glassé's default look and looked almost the same. Android.
- **Material 3 Expressive** looks finished. Usage bars now show the moving Expressive wave, with a stop dot at the end of the track. A window that is running out early turns the bar and its pace pill red. Meters sit together as one grouped list, cards carry their label at the top, and the title and numbers use heavier type. Pulling down on the dashboard refreshes, with Expressive's loading indicator. Settings now open in the same design: Material switches, sliders, choice dialogs and grouped rows, running the same settings as the other designs. The other pages, such as Providers or Usage history, still open in their One UI form. Android.

### Fixed

- Liquid Glass usage cards read "Resets Resets in 3h"; they now read "Resets in 3h". Android.
- Claude Code no longer loses its sign-in when two refreshes overlap. Claude sign-in tokens can only be used once, and a saved-reset claim could resend one that a usage refresh had already replaced, which Anthropic answers with "Refresh token not found or invalid". The app now uses the newer token and saves each new token the moment it arrives. Android and iPhone/Mac.
- When Anthropic does reject a Claude sign-in, the Claude Code tab says so in plain words and shows a **Sign in with Claude again** button. Signing in again keeps your usage history and settings. Android, in every design including Liquid Glass.
- A usage card whose reset time has passed since the last refresh shows it as refilled ("Reset passed · refresh to update") instead of the old number next to "Resets now". Android, in every design including Liquid Glass.
- Claude Code usage is sturdier after Anthropic's plan change. On Android the usage request now matches Claude Code 2.1.316, and if Anthropic refuses its saved-reset part, the app retries plain usage instead of failing. On every app a 403 is treated as a temporary refusal instead of "your plan has no usage limits", and your sign-in is always kept. Accounts with no 5-hour limit (verified Ultra) load normally.
- Accounts without a 5-hour limit show the weekly meters with a short "No 5-hour limit on this plan" note instead of a missing card. Home screen widgets set to the 5-hour meter show the weekly one instead of "Unavailable", the live notification follows the weekly limit, and the watch glance shows only the weekly meter instead of "5h --". Android, iPhone/Mac and Windows.

## 2.9.0-alpha.17 — 2026-11-06

### Changed

- Liquid Glass lists you can rearrange (dashboard tabs, meter order, and others) now use Glassé's reorder list like iOS: tap Edit at the top right to show the ≡ handles, or touch and hold a row to drag it without entering Edit.
- Updated Glassé, which brings its latest switch and scrolling fixes.

### Fixed

- Toggles on the widget settings pages no longer lag. Each tap redrew the widget preview image before the switch could move; the preview now refreshes a moment after you stop tapping.

## 2.9.0-alpha.16 — 2026-11-05

### Changed

- Liquid Glass action rows such as "Check for updates" read in the normal text color like iOS Settings; only destructive ones are red.
- Home screen widgets go back to their regular look in Liquid Glass. The glass surface in 2.9.0-alpha.15 replaced the background that Samsung's launcher blurs, so widgets lost their blur and looked rough.

### Fixed

- Pull to refresh works on every dashboard page. A page shorter than the screen, such as a provider with only a couple of cards on a tall phone, ignored the pull.

## 2.9.0-alpha.15 — 2026-11-05

### Changed

- **Liquid Glass** now runs on Glassé's latest version, with its iOS 26 pop-up menus, pull to refresh, progress bars, spinners and text fields.
- Dashboard: Edit and Settings are two glass buttons at the top right, with SF Symbols-style icons. The refresh button and the ⋯ menu are gone. Pull down to refresh, and open Usage history from its card.
- Usage cards have a springing progress bar and a pace row: "At this pace it lasts 1d 6h", or "Running out early" in orange.
- Usage history shows one window at a time, picked with a segmented control, with a legend under the chart in place of the explanation paragraph.
- Settings choices open an iOS 26 pop-up menu from the row instead of a new page.
- Add account and every provider's sign-in page are now Liquid Glass pages, with search, a large device code with a spinner, and text fields with a Paste button.
- In Liquid Glass, home screen widgets get a glass look: a see-through fill, a top sheen and a bright rim. Switching designs redraws them.

### Fixed

- Back on the dashboard no longer closes the app while a sheet, menu or alert is open. The same goes for widget settings with a sub page open.
- Android's predictive back-to-home animation now plays from the Liquid Glass dashboard.
- A back gesture the system cancels no longer leaves a page stuck mid-swipe.
- Full-width buttons in Liquid Glass cards no longer run into the card's edges.

## 2.9.0-alpha.14 — 2026-11-03

### Added

- **Liquid Glass design** (Settings › Appearance): a third design next to One UI 8.5 and One UI 9, built from Glassé's glass components. Every screen has a glass version: the dashboard, Settings, Dashboard tabs, Lock screen widgets, About, Usage history, Reset credits, Edit dashboard, App update, Release history, home and lock-screen widget settings, alerts, sheets and the Add reminder time picker. Every setting keeps its existing behavior. One UI 8.5 and One UI 9 are unchanged. The APK is about 20 MB larger.
- Desktop live usage: the Windows tray icon and the Mac menu bar item now follow the same rules as the Android live notification. They show the AI app in front first (ChatGPT or Codex, Claude, Cursor and others), then your chosen service, and the window with less left, or the time until reset once a limit is used up. Windows draws a ring or number icon at the tray's real size, with a flyout of every service's bars when clicked. The Mac shows a drawn ring that turns orange and red as you run low. Linux gets the ring style and the Service and Percentage shown settings. Each app has a new Live usage section in Settings.

### Changed

- Desktop apps reach parity with the phone apps: Windows and Linux cover all 24 services, with usage history charts and insights, Edit dashboard, Add account search, device-code, pasted-token and API-key sign-in, Codex reset auto-use, and a first-run welcome page. Windows uses a native title bar and navigation view with low and failing badges. Linux uses libadwaita dialogs and boxed lists. The Mac app gains an Add Account button in the sidebar and File › Add Account… (⌘N).

## 2.9.0-alpha.13 — 2026-10-28

### Changed

- Usage history shows the Weekly chart first and the 5-hour chart below it, on the dashboard card and the full history page.
- The live notification is simpler: the title says what is left in the focused window ("Weekly · 48% left"), and the line under it says when that window resets, then the other window. The estimate only appears when usage is running fast.
- Cursor cards say "Weekly" or "Monthly" instead of "Included usage · Weekly".

### Fixed

- The dashed projection on usage charts now climbs at your current pace up to the reset when you will not run out first, instead of drawing a flat line.
- Usage cards no longer show "Est. depleted" next to a limit that is already at 0%.

## 2.9.0-alpha.12 — 2026-10-24

### Fixed

- One UI 9 design: the floating provider tab bar's blur now follows the content as it scrolls underneath, instead of sometimes keeping the blur from when the page loaded.

## 2.9.0-alpha.11 — 2026-10-23

### Added

- Codex Meter for Mac (`macos/`): a native SwiftUI app with a menu bar meter that shows the allowance closest to running out, a panel with every provider's meters, a sidebar window with the same Codex, Claude Code and Cursor dashboards as the iPhone app, a Settings window (⌘,), Usage menu shortcuts (⌘R, ⌘1–⌘9), Open at login, and desktop widgets. It keeps refreshing, alerting, and auto-using expiring resets from the menu bar after its window closes.
- Codex Meter for Windows (`windows/`): a native WinUI 3 app for Windows 11 on x64 and ARM64, with Mica, a sidebar with Codex, Claude Code and Cursor dashboards, browser sign-in on an Accounts page, a full Settings page, and a notification-area icon that shows your remaining percentage, plus toasts. Run it with `--demo` to see sample data without signing in.
- Linux desktop app: a native GTK 4 + libadwaita app with a dashboard for Codex, Claude Code and Cursor, accounts, settings, a tray icon with your remaining percentage, desktop notifications, start at login, and credentials protected by the login keyring. It shares the Windows app's C# core. Each release includes a `.deb` and an AppImage for x86-64 and ARM64.
- iPad: the provider tabs become a sidebar (or a floating top tab bar the sidebar button toggles), with Settings at the bottom of the sidebar, so every connected provider is one tap away instead of behind a More tab.
- iPad: dashboards lay cards out in one, two or three columns from the width they actually get, so full screen, Split View, Slide Over and Stage Manager windows of any size re-flow live. Cards in a row share one height, the usage history chart keeps a full row, and the signed-out card stays card-sized.
- iPad keyboard and menu bar: ⌘1–⌘9 jump to a provider tab, ⇧⌘] and ⇧⌘[ move between tabs, ⌘R refreshes the visible tab and ⌘, opens Settings. With a trackpad or mouse, hovering scrubs the usage history charts and picker rows highlight.
- Apple Watch app (watchOS 26) with complications. It shows every provider the iPhone app tracks, one page per provider, with a ring, per-limit bars and the next reset. Circular, corner, rectangular and inline complications show the followed provider's main limit, plus a next-reset complication. Data comes from the iPhone over WatchConnectivity; tokens never leave the phone.

### Changed

- iOS catches up with Android's providers: the 21 providers Android added (Copilot, Kimi Code, Factory, Grok, Devin, Windsurf, Augment, Cline, OpenCode Go, MiniMax, Z.ai, Kiro, Amp, OpenRouter, Kilo, Codebuff, Synthetic, Chutes, v0, Warp) can be connected on iPhone too, each with its own tab, Keychain item and cache. Antigravity stays Android-only for now (its Google sign-in needs a local callback), and OpenRouter connects with an API key on iPhone.
- iOS Settings → Accounts matches Android: one Accounts row listing the connected services opens an Accounts page with the connected accounts (email · plan, or "API key" / "Signed in") and an Add account row that opens a searchable provider picker. The Cursor row now shows its email. One sign-in screen handles device codes, pasted tokens and API keys, and marks experimental providers.
- iOS first-run setup uses the same searchable multi-select provider picker as Android, and Settings → Providers lists only tabs that are on and connected accounts.
- iOS home-screen and lock-screen widgets can each show a chosen provider (Codex, Claude Code, Cursor or any connected provider). Existing widgets keep showing Codex.
- Wear OS follows all 24 providers, not only Codex. The phone sends every enabled or signed-in provider's latest usage to the watch. The watch app lists them with their logos, and tapping one makes tiles, complications and the live monitor follow it. By default the watch follows the phone's selected tab.
- Wear tiles and complications are named by role (Usage overview, Main limit, Second limit, Next reset, Live monitor, Both limits) and use each provider's own windows: 5h and weekly for Codex, monthly for Cursor, named extra limits for Claude Code and Copilot.
- "Refresh on phone" from the watch refreshes Claude Code, Cursor and the other providers too.

### Development

- `DashboardGrid` in CodexMeterCore decides columns and rows, with unit tests. `IPadLayoutTests` checks the columns, sidebar and shortcuts on iPad simulators, and iOS CI runs it on iPad Pro 13-inch (light and dark), iPad Air 11-inch and iPad mini, uploading the stills as `ipad-gallery`. The main UI suite also runs on iPad Pro.
- `WatchProvidersSelfTest` covers watch meter ordering, the provider payload round trip and which provider the watch follows. `WatchSyncTests` covers the same on iOS.
- iOS CI builds the watch app on a watch simulator and uploads a `watch-gallery` artifact of screenshots.

## 2.9.0-alpha.10 — 2026-10-23

### Changed

- Settings cleaned up, with nothing removed. The main list is grouped as Accounts, Live usage and Usage, and **Now Bar** is the first row under Live usage with a summary of what starts it.
- **Now Bar → Start automatically → While using AI apps** is one switch row: the switch turns it on or off, and tapping the row opens the app list. The on/off subtitle and the long description are gone. The app list is one card, each app naming the dashboard it shows, with a single **Add an app**.
- **Providers** is now **Dashboard tabs**, with a one-line hint. **Accounts** is one card ending in **Add account**.
- **Refresh & dashboard**: refresh mode and interval are one list (Automatic, or every 5 minutes to 2 hours). The eight Codex card switches are gone because **Edit dashboard** already has them; it now asks which tab to edit.
- **Reset credits** has its own page. Follow-up options (when to use a reset, the threshold, the window) only appear once their switch is on.

### Fixed

- Show in AI apps is now reliable and near-instant. It used to guess the app in front from "new screen" events, which launchers don't always send. Reopening an app from Recents or its icon could do nothing, going home could leave the notification stuck, and a 200 ms event batching delay made timing uneven. It now shows the moment a matched app's screen opens, double-checks which app window is actually in front on a background thread every time anything changes, gets each change with no batching delay, and hides about 0.6 s after you leave (was 1.5 s). Switching between matched apps swaps the provider in place, without hiding and re-showing. Because of this change, Android now describes app detection as able to view the screen. Codex Meter still reads only the package name of the app in front.

## 2.9.0-alpha.9 — 2026-10-23

### Added

- **Show in AI apps** (Settings → Now Bar → Show while an AI app is open, off by default): the live notification appears on its own while ChatGPT, Claude, GitHub, Grok or Kimi is open, showing that app's own provider (ChatGPT shows Codex, Claude shows Claude Code, GitHub shows Copilot), and goes away about a second after you leave. Each connected provider lists its official apps with a switch, and **Add an app** matches any other installed app. It finds the app in front through an Android accessibility service that only reads the app's name: no screen content, no taps or typing, and nothing leaves the phone. A monitor you started yourself or one started by a low allowance is never replaced, and dismissing it hides it until you leave that app.

## 2.9.0-alpha.8 — 2026-10-22

### Fixed

- One UI 9 design: the lighter grey band behind the floating back button is gone. SESL fades list edges on Android 16+ by erasing what the list drew there, and SESL's settings list draws its cards as a grey background under black gaps, so the fade uncovered that grey. Every One UI 9 list now fades into the exact page color instead.
- One UI 9 design: the expanded header is as tall as on classic pages again. It was a status bar's height short, so the large title sat too high.

## 2.9.0-alpha.7 — 2026-10-22

### Fixed

- One UI 9 design rebuilt from scratch on SESL9's own page parts instead of the One UI 8 header. Headers are fully transparent: the large title collapses, then the back and action buttons float in their own pills while content scrolls under them and fades out below the status bar. No filled or rounded header box anywhere. The dashboard's provider tabs are a floating tab bar, and widget settings keep a floating Cancel / Save bar.
- Classic design is unchanged from alpha.6, with the collapsed header pinned at every scroll depth.
- Claude Code saved resets: sign-in and token refresh now ask for Claude Code's full scope list, so Anthropic treats the app as Claude Code. Existing sign-ins pick up the new scope on their next refresh. Settings → Refresh & usage → **Check with Anthropic** makes one fresh usage request and shows exactly what came back, with a Copy button.

### Changed

- Lock screen gauges lead with the weekly limit instead of the five-hour one. When a dial can only show one window (two providers on the Gauges widget, or the single Gauge widget), it now shows weekly on the main ring and five-hour as a thinner, lighter ring inside it, so both stay visible. On iOS, the circular lock screen widget puts weekly on the bold outer ring, and the rectangular and inline widgets list weekly first.

## 2.9.0-alpha.6 — 2026-10-22

### Fixed

- One UI 9 design: settings pages no longer wrap scrolled content in a filled box with One UI 8 corners. Content now fades under the floating buttons.
- Classic design: the collapsed header stays pinned while you scroll instead of scrolling away, and widget settings show the Cancel / Save bar again.
- Claude Code saved resets now show up for accounts that have them: usage and reset requests now identify as Claude Code, since Anthropic decides reset eligibility per client. Settings → Refresh & usage has a new **Your resets** row for Codex and Claude Code that shows the count and next expiry, or why no resets are offered.

### Changed

- Turning the One UI 9 design on or off restarts the app and reopens Settings → Appearance, so every page is rebuilt cleanly.

## 2.9.0-alpha.5 — 2026-10-22

### Added

- Claude Code saved resets (claude.ai Settings → Usage → Limit resets) now show on the Claude tab in the same Reset credits card and reset screen as Codex, with Reset for free, and Settings has the same before-expiry and running-low auto-use rules. Anthropic can hold a reset until you hit a usage limit; the button then stays off and the reset screen says why.

### Changed

- Settings → Appearance → One UI 9 design switches the app between its classic look and Samsung's One UI 9 design, in place, with no second app to install. One UI 9 uses SESL9's own components: the floating toolbar, the floating blurred tab bar, native fading list edges and the floating Cancel / Save bar in widget settings. The "Try One UI 9" row and the separate preview app are gone.

### Development

- The whole app now builds on SESL9 (vendored in `android/vendor/m2`) and needs JDK 25. The classic design restores SESL8's sizes and colors through generated resource overrides (`android/ci/classic-design-resources.py`). `-PoneUi9` is gone.

## 2.9.0-alpha.4 — 2026-10-22

### Added

- Settings → Appearance → Try One UI 9 opens the One UI 9 preview, a separate app that installs next to Codex Meter. It runs on Samsung's One UI 9 (SESL9) libraries and uses their One UI 9 components: the floating toolbar, the floating blurred tab bar, native fading list edges and the floating Cancel / Save bar in widget settings. The standard app keeps its One UI 8 design.

### Changed

- The Codex tab's title now reads "Codex", like the Claude Code and Cursor tabs.
- Edit dashboard is per provider: each tab keeps its own card order and hidden cards, and the editor lists only the cards that provider reports (Codex keeps its existing layout).
- Cursor limits follow Cursor's current plans. Current plans show one "Included usage · Weekly" card plus any "Additional usage" cap. Legacy monthly plans (Pro, Pro+, Ultra) show "Included usage · Monthly", the "Cursor models" and "Other models" pools, and on-demand spend. The outdated "Auto + Composer" and "API models" labels are gone, and Cursor card titles no longer truncate.
- Antigravity, Augment, Chutes, Codebuff, Devin, Factory, Grok, Kilo Code, Kiro, Synthetic and Z.ai now show their own logos in tabs, Accounts, Add account, first-run setup and widgets, instead of a letter in a box.

### Development

- Self-tests cover Cursor weekly vs legacy plans and the per-provider dashboard sections.
- Android: AGP 9.4.1, Gradle 9.8.2, Wear Tiles 1.6.2, ProtoLayout 1.4.2, androidx.core 1.19.1, Guava 33.7.2.
- `-PoneUi9=true` builds the One UI 9 preview (`dev.bennett.codexmeter.oneui9`). It needs JDK 25 and GitHub Packages access, or SESL9 in `android/vendor/m2` (`android/ci/vendor-sesl9.sh`).

## 2.9.0-alpha.3 — 2026-10-21

### Changed

- Lock-screen widgets now show the same providers as the rest of the app. A widget you haven't set up shows the providers you're signed in to, in tab order, each labeled with its name and icon, instead of falling back to a single provider.
- New Settings → Lock screen widgets page lists every Codex Meter widget on the lock screen and opens its settings to pick providers and meters, since One UI often skips the widget's own setup screen.
- Lock-screen gauges only show meters a provider reports: a Cursor gauge shows its included-usage dial instead of an empty 5-hour dial, and a single meter draws one centered dial.
- The Bars lock-screen widget is gone from the picker. Bars already placed now draw dials.

## 2.9.0-alpha.2 — 2026-10-21

### Changed

- First-run setup now asks which providers to track (Codex, Claude Code, Cursor; nothing preselected), then walks through signing in to each one, with Skip for now on every sign-in step. Android and iOS.
- Upgrading installs that never chose providers start with only Codex on (plus any provider already signed in), so a Codex-only install shows no tab bar.
- Accounts moved off the dashboards: Settings opens with a compact Accounts list, one row per provider on the dashboard with its email and plan, to sign in or out. Settings → Providers shows who is signed in.
- Home-screen widgets can show any provider, and larger sizes can stack several providers.
- The lock-screen widget picker is down to three widgets (Gauge, Gauges, Bars) that work with any provider. The seven older lock-screen widgets are hidden from the picker but keep working where they are already placed.
- Widget meter labels no longer start with "Codex ·".

### Development

- Self-tests cover the upgrade default for provider tabs and the provider-count-aware setup steps.

## 2.9.0-alpha.1 — 2026-10-19

### Added

- Provider switcher: stock One UI bottom tabs (oneui-design `BottomTabLayout`, icon over label) on the dashboard switch between Codex (default, unchanged) and Claude Code, and the large title follows the selected provider. Claude Code signs in with a Claude account through the same OAuth + PKCE loopback flow as the Claude Code CLI. Its cards follow the `limits` list from the route behind Claude Code's `/usage`: usually just 5-hour and weekly, plus a model-only allocation such as Fable when the account has one, and extra usage when enabled. Providers are pluggable through `UsageProvider`.
- Claude Code usage history, burn charts, and pace estimates are kept separately from Codex.
- Home-screen widgets can show Claude Code (widget settings → Provider); tapping one opens the Claude Code tab.
- iOS: a bottom provider switcher with a Claude Code tab (Claude sign-in by pasting the code Claude shows, usage cards driven by the same `limits` list, plan badge, demo data).
- Background refresh covers Claude Code at most every 30 minutes with a one-hour back-off after HTTP 429, and keeps running when only one provider is signed in.
- Cursor tab on Android and iOS: sign in with Cursor through the same browser login the Cursor editor uses (no code to paste), then see included usage for the billing cycle, Auto + Composer and API model pools, on-demand spend in dollars, the plan badge, and the reset date. Background refresh checks Cursor at most every 15 minutes.
- Settings → Providers chooses which providers appear on the dashboard and in what order (drag to reorder, like Edit dashboard; all on by default, at least one stays on). With two or more on, only their tabs show; with one on, the bottom tabs are hidden. Tabs and settings rows use each service's own mark (OpenAI, Claude, Cursor) from Simple Icons. On iOS the switcher is now the native tab bar.

### Development

- Self-tests cover provider ids/defaults, Claude usage parsing (fractional-second ISO resets, null windows, the `limits` list with Fable-only and unknown scoped meters, extra usage, plan badges), the Claude authorize/token request shapes, and Claude refresh pacing.
- Self-tests (Android and `CodexMeterCore`) cover Cursor usage parsing (epoch-ms and ISO billing cycles, string-encoded numbers, computed plan percent, personal vs team on-demand caps, plan badges), the PKCE login/poll URLs, JWT expiry, token parsing, and auth-error detection.
- Self-tests cover the enabled-provider list (defaults, unknown ids, saved tab order, keeping the last provider on, and selection fallback); the iOS demo gallery captures the two-tab, single-provider, and Providers settings screens.

## 2.8.0 — 2026-08-14

### Added

- Free-tier monthly Codex window support: when a paid plan expires to Free, OpenAI's ~30-day monthly limit is parsed and shown across the dashboard, usage history, widgets, Wear, Now Bar, alerts, and adaptive refresh instead of failing with "no recognizable Codex usage data" and leaving a stale Pro badge (#92).
- Opt-in diagnostic log tracing and export (unlock via About header taps) with sanitized JSONL logs for process lifecycle, refresh, OAuth, updates, widgets, Now Bar, and Wear sync (#91).
- Usage-history Customize checklist so chart guide, previous-window list, insight rows, and value estimates can be shown or hidden individually; defaults are decluttered (#89).

### Development

- Monthly-window self-tests cover free-tier parsing, 28/31-day drift, long-window fallbacks, and the downgrade→resubscribe flow; diagnostic sanitizer coverage redacts credentials, emails, JWTs, and OAuth query params (#91, #92).
- Removed the one-time alpha bootstrap workflow now that the `alpha` branch exists, and documented release-channel etiquette for agents (#90).
- Previously shipped to the alpha channel as 2.8.0-alpha.1.

**Full Changelog**: https://github.com/BenItBuhner/Codex-Meter/compare/v2.7.0...v2.8.0 <!-- pragma: allowlist secret -->

## 2.7.0 — 2026-08-01

### Added

- Alpha release channel with one-tap in-place switching: Settings → Updates → Update channel opts into signed alpha builds, and returning to stable installs in place with no uninstall or data loss because alpha builds share the stable signing key and version code (#88).
- Scrubbable usage-history analytics with per-window insights and plan value estimates (#87).

### Development

- New `alpha` branch (auto-created by CI after merge) feeds `v*-alpha.N` tags; release CI publishes them as GitHub prereleases, never marks them latest, and enforces the shared-versionCode invariant that keeps channel switching downgrade-free (#88).
- Channel-selection self-tests and source guards cover stable/alpha update picks, alpha-to-alpha upgrades, stable promotions, and the return-to-stable flow (#88).

**Full Changelog**: https://github.com/BenItBuhner/Codex-Meter/compare/v2.6.10...v2.7.0 <!-- pragma: allowlist secret -->

## 2.6.10 — 2026-07-31

### Fixed

- Restored dashboard auto-hide for empty usage-credit, reset-credit, and missing-window cards. The 2.6.9 blank-placeholder behavior was intended for widgets only, not the home-screen dashboard (#85).
- Adaptive widget layouts no longer flip to two oversized dials after Spark/additional-limit meters are stripped from saved configs; multi-usage selections migrate to Codex 5-hour + weekly so tall Adaptive sizing keeps progress bars (#85).

### Development

- Shared `resolveVisibleForWidget` / `resolvedSingleUsageMetric` migration helpers cover layout, slot selection, config load, and lock widgets (#85).

**Full Changelog**: https://github.com/BenItBuhner/Codex-Meter/compare/v2.6.9...v2.6.10 <!-- pragma: allowlist secret -->

## 2.6.9 — 2026-07-31

### Changed

- Home and lock widgets no longer offer GPT-5.3-Codex-Spark or other model-specific additional limits; those stay on the in-app dashboard while widgets stick to Codex 5-hour/weekly, next-reset, and reset-credit meters (#84).
- Customize widget meters can be drag-reordered (handle or long-press) so slot priority matches the user’s order, matching Edit dashboard (#84).
- Edit dashboard / Settings visibility switches keep toggled-on sections on screen with a blank dash placeholder when OpenAI has not reported data yet, instead of auto-hiding empty cards inconsistently (#84).

### Fixed

- Selected widget meters that fail to resolve no longer collapse away; they keep a reserved blank dash slot (#84).

### Development

- Regression coverage and static checks updated for the Spark-free widget catalog, dashboard placeholder visibility, and widget meter drag reorder (#84).

**Full Changelog**: https://github.com/BenItBuhner/Codex-Meter/compare/v2.6.8...v2.6.9 <!-- pragma: allowlist secret -->

## 2.6.8 — 2026-07-30

### Added

- Home and Samsung lock widget layout and meter customization: choose Adaptive by size, Dials, or Progress bars, and pick which Codex 5-hour/weekly, Spark (and other additional limits), next-reset, and reset-credits meters fill the available slots (#81).

### Changed

- Reset-credit cards auto-hide when no resets are available (or inventory is unknown), even if the Edit dashboard / Settings toggle is on (#80).
- Usage history stays off the Android dashboard until OpenAI reports a 5-hour or weekly window that can feed a burn chart (#80).
- Usage-credit balances on iOS now hide when zero, near-zero, or negative, matching Android (#80).

### Fixed

- Low-usage alerts no longer re-fire every adaptive refresh when OpenAI's reset timestamp drifts slightly within the same usage window; dedupe uses the same reset-time tolerance as local usage history (#82).

### Development

- Expanded regression coverage for widget meter catalogs, capacity/layout preferences, stale meter fallbacks, reset-credit auto-hide, and low-usage alert dedupe (#80, #81, #82).

**Full Changelog**: https://github.com/BenItBuhner/Codex-Meter/compare/v2.6.7...v2.6.8 <!-- pragma: allowlist secret -->

## 2.6.7 — 2026-07-26

### Fixed

- Reset credits is now a real dashboard section that reorders and hides with the rest of the cards, so the usage-history chart (or any other card) can be placed below it instead of the reset-credit card being pinned to the bottom of the page.
- Reset-credit and usage-credit cards use bold in-card titles with left-aligned icon rows, matching the 5-hour, weekly, and usage-history cards, instead of external One UI section separators that looked inconsistent.

**Full Changelog**: https://github.com/BenItBuhner/Codex-Meter/compare/v2.6.6...v2.6.7 <!-- pragma: allowlist secret -->

## 2.6.6 — 2026-07-26

### Added

- Visibility switches on every row of the Edit dashboard screen so usage cards can be hidden or shown while rearranging them, including per-limit control over model-specific limits.
- Usage history is now its own dashboard section that can be reordered and hidden like the other usage cards, on the dashboard, in the edit screen, and in Settings → Refresh & usage.

### Changed

- 5-hour and weekly burn charts hide from the usage-history card and the full history screen while OpenAI has not yet reported those windows, replacing blank "Waiting for usage data" graphs with a short hint.
- Dashboard visibility and hidden-section choices are included in settings transfer exports.

### Fixed

- Restored the One UI design for the reset-credit and usage-credit sections (left-aligned Separator + CardItemView rows with icons) that regressed when the dashboard-reordering branch superseded the earlier cleanup.

### Development

- Expanded dashboard-section regression coverage for the usage-history key and hidden-section CSV round-trips.

**Full Changelog**: https://github.com/BenItBuhner/Codex-Meter/compare/v2.6.5...v2.6.6 <!-- pragma: allowlist secret -->

## 2.6.5 — 2026-07-26

### Added

- Local usage history with burn charts and trend-aware pace estimates on Android (#68).
- Responsive single-dial home and lock widgets that adapt to available size, with clarified Both windows / 5-hour only / Weekly only allowance labels (#68).
- Samsung One UI Watch modular tile metadata (2×2 overview and 2×1 focused tiles), redesigned Wear tiles with One UI Sans and battery-style dials, and adaptive complications across short/long text, ranged/goal progress, and image slots (#67).
- Drag-to-reorder dashboard sections for the 5-hour limit, weekly limit, additional model limits, and usage-credit balance, with a One UI edit screen and Settings entry (#75).

### Changed

- Usage-credit balance cards auto-hide when the balance is empty, effectively zero, negative, or absent (#75).
- Widget allowance configuration uses shared One UI choice dialogs on phone and lock widgets (#68).

### Development

- Expanded regression coverage for usage-credit auto-hide, dashboard section order merging, Wear modular tile metadata, and widget configuration wiring (#67, #68, #75).

**Full Changelog**: https://github.com/BenItBuhner/Codex-Meter/compare/v2.6.0...v2.6.5 <!-- pragma: allowlist secret -->

## 2.6.0 — 2026-07-26

### Added

- Adaptive Automatic refresh mode (now the default) on Android and iOS that adjusts cadence from remaining quota, reset proximity, recent engagement, quiet hours, accelerated usage, and failure backoff (#71).
- Adaptive usage dashboard sections that preserve and render named additional rate limits such as GPT-5.3-Codex-Spark, hide missing standard windows instead of empty cards, and show purchased usage-credit balances separately from reset credits (#70).
- Dashboard visibility controls on Android and iOS so users choose which usage sections appear, with safe migration defaults (#70).

### Changed

- Bumped the phone app One UI design library to `0.9.14+oneui8` and refreshed `android/vendor/m2` so offline and release builds resolve the new AAR without GitHub Packages auth (#69).
- Wear freshness now follows the phone's effective adaptive refresh cadence (#71).

### Fixed

- One UI `CardItemView` / `SwitchItemView` RTL summary `ViewStub` inflation via the design-library update (#69).

### Development

- Expanded regression coverage for adaptive refresh policy and settings migration, plus adaptive usage parsing for partial, additional-only, and credit-only responses (#70, #71).

**Full Changelog**: https://github.com/BenItBuhner/Codex-Meter/compare/v2.5.0...v2.6.0 <!-- pragma: allowlist secret -->

## 2.5.0 — 2026-07-20

### Added

- Fully supported Wear OS companion distribution with a signed `CodexMeter-Wear-2.5.0.apk` attached directly to tagged GitHub Releases alongside the phone APK and shared SHA-256 checksums.
- Phone-owned Wear sync status for sign-in, refresh progress and errors, initial state hydration, plan and limit state, reset credits, stale data, and accelerated-usage settings.
- Wear-native overview, five-hour, weekly, reset, and monitor Tiles; five-hour, weekly, dual-usage, and next-reset Complications; and promoted Ongoing Activity / Live Update monitoring.

### Changed

- Rebuilt the Wear application and settings around the Android app's One UI dark design system, including matching typography and colors, 28dp cards, true pill controls, stateful touch feedback, One UI settings rows, rounded popup surfaces, and explicitly styled switches.
- Replaced the generated Wear launcher artwork with the canonical Android adaptive icon layers and official Codex notification mark.
- Updated the Wear dependency stack and compile platform for current Wear OS releases while retaining the Android 16 target behavior.
- Exhausted Now Bar and Wear monitor windows show their reset countdown instead of an unhelpful `0%`.

### Fixed

- Signing out now clears cached usage and active monitor state from the watch instead of leaving sensitive stale data visible.
- Wear refresh requests return useful progress and failure state, and opening the app hydrates existing Data Layer items instead of waiting indefinitely for another phone update.
- Removed release-accessible fake demo usage that could be mistaken for real synchronized account data.
- Tagged GitHub Releases now publish the Wear APK that CI already built and verified, rather than listing it only inside `SHA256SUMS.txt`.

### Development

- Added regression coverage for Wear sync clear/status payloads, pace settings, stale/account formatting, canonical icon parity, and public Wear release-asset publication.
- Validated the signed app, all five Tiles, all four Complications, One UI controls, and promoted ongoing notification on a Wear OS 6.1 large-round emulator.

**Full Changelog**: https://github.com/BenItBuhner/Codex-Meter/compare/v2.4.3...v2.5.0 <!-- pragma: allowlist secret -->

## 2.4.3 — 2026-07-19

### Fixed

- Android Appearance settings once again show the illustrated Light and Dark theme options side by side, while preserving System default behavior in the redesigned settings navigation.

**Full Changelog**: https://github.com/BenItBuhner/Codex-Meter/compare/v2.4.2...v2.4.3 <!-- pragma: allowlist secret -->

## 2.4.2 — 2026-07-19

### Added

- Off option for accelerated-usage warning sensitivity so pace estimates can stay on without orange warnings or matching Now Bar auto-start triggers (#57).
- Reset-credit expiration details on the dashboard and inventory, with soonest-expiring credit selection when redeeming (#56).

### Changed

- Android Settings now uses a compact hub with focused destinations for appearance, refresh and usage, notifications, Now Bar, updates, transfer, and privacy, revealing nested options only when their parent feature is enabled (#54).

### Fixed

- Onboarding card spacing and Settings bottom padding no longer collapse against adjacent cards or the screen edge (#52).

**Full Changelog**: https://github.com/BenItBuhner/Codex-Meter/compare/v2.4.1...v2.4.2 <!-- pragma: allowlist secret -->

## 2.4.1 — 2026-07-18

### Fixed

- Active Android Live Updates now rebuild immediately after promoted-notification access changes, allowing automatic Samsung fallback and explicit Android modes to adopt the newly available notification contract without waiting for another usage refresh.
- Live Update construction now adapts to both early Android 16's required colorization and newer uncolorized promotion rules, while notification and Now Bar Codex icons use SystemUI-compatible vector syntax.

**Full Changelog**: https://github.com/BenItBuhner/Codex-Meter/compare/v2.4.0...v2.4.1 <!-- pragma: allowlist secret -->

## 2.4.0 — 2026-07-18

### Added

- Wear OS companion app with phone↔watch usage and settings sync, plus tiles, complications, and Ongoing Activity monitoring (#37).
- Native iOS / iPadOS SwiftUI + WidgetKit client under `ios/`, with portable meters, reset credits, notifications, widgets, and Keychain-backed auth (#22).
- Android settings transfer: export and import selected sections (app settings, notifications, Now Bar, and ChatGPT authentication) from Settings, with explicit warnings when authentication tokens are included (#42).
- Configurable automatic update-check frequency (hourly through weekly) and optional notifications when a newer signed GitHub release is available (#38).
- Estimated usage-pace projections on dashboard cards, with One UI functional-orange accelerated-usage warnings and matching Now Bar auto-start / regress triggers (#47).
- Widget Background toggle in customize settings so the fill can be turned off entirely, matching official One UI widgets (#45).
- Optional Material You accent switch under Settings → Appearance so app accents can follow the system palette on Android 12+ (#44).

### Changed

- Default theme primary now uses Samsung's official One UI Primary (`#0381FE`) instead of the lighter SESL sample blue (#44).
- Widget opacity control now uses three discrete fill strengths (56% / 88% / 100%) instead of four, aligning with current Samsung One UI widget transparency steps (#45).
- Centralized observation-based reset fallback timing across the dashboard, widgets, Now Bar, and Wear surfaces (#47).

### Fixed

- App update and release-history loading now use a centered One UI spinner instead of clipped status text, and idle update cards no longer reserve empty status-line height (#35).
- Live usage monitor progress uses a plain progress dot, and Now Bar / notification Codex logos adapt to light, dark, and accent surfaces without a baked white square (#36).

### Development

- Restructured the repository into `android/` and `ios/` project roots with thin root wrappers and CI paths pointed at the Android Gradle tree (#40).
- Fixed Cloud Agent setup to invoke `android/gradlew` after the monorepo move (#43).
- Expanded regression coverage for settings transfer, update-check frequency, usage-pace estimates, widget fill controls, Material You preferences, and Wear sync contracts (#37, #38, #42, #44, #45, #47).

**Full Changelog**: https://github.com/BenItBuhner/Codex-Meter/compare/v2.3.3...v2.4.0 <!-- pragma: allowlist secret -->

## 2.3.3 — 2026-07-17

### Added

- Now Bar percentage-mode setting (Auto / 5-hour / Weekly) so users choose which remaining percentage drives the live-monitor progress pill, with Auto locking to the auto-start trigger window (#33).
- A `W` prefix on weekly-focused Live Update critical percentage text so compact Now Bar labels stay unambiguous (#33).

### Changed

- Normalized app, widget, and OAuth action buttons to a full-pill corner radius with consistent 18sp bold labels (#32).
- Changing the percentage mode safely re-posts an active monitor, stops the session if posting fails, and restores Auto to the original auto-start lock instead of recomputing lower-remaining (#33).

### Development

- Expanded regression coverage for Now Bar percent-mode selection, settings-change focus resolution, and failed applyPercentModeChange cleanup (#33).

**Full Changelog**: https://github.com/BenItBuhner/Codex-Meter/compare/v2.3.2...v2.3.3 <!-- pragma: allowlist secret -->

## 2.3.2 — 2026-07-15

### Added

- Automatic, Android Live Update, and Samsung compatibility display modes for the live usage monitor, including manual overrides for firmware that reports promotion support incorrectly (#29).
- In-app diagnostics for blocked app or live-monitor notifications, plus Samsung developer-option and firmware troubleshooting guidance (#29).

### Changed

- Automatic mode now keeps Android 16 Live Update promotion and Samsung's private ongoing-activity payload mutually exclusive, selecting the Samsung-compatible path when Galaxy firmware denies platform promotion (#29).
- Changing display modes safely re-posts an active monitor, while failed updates clear stale active state instead of leaving the monitor stuck (#29).

### Fixed

- Restored Now Bar presentation on compatible Samsung firmware where third-party Android Live Updates remain ordinary notifications, while retaining the standard notification fallback on unsupported firmware (#29).

### Development

- Added regression coverage for display-mode normalization and firmware-dependent mode resolution (#29).

**Full Changelog**: https://github.com/BenItBuhner/Codex-Meter/compare/v2.3.1...v2.3.2 <!-- pragma: allowlist secret -->

## 2.3.1 — 2026-07-14

### Added

- Configurable Now Bar auto-start when remaining allowance reaches a selected threshold for five-hour, weekly, or both windows, with dismiss suppression for the remainder of that monitor window (#26).
- Rendered GitHub release-note Markdown (headings, lists, emphasis, and links) in the App update What's new section and on each Release history card (#25).

### Changed

- Treats every release before 2.3.0 as irreversible for in-app installs and sends users to the GitHub release page instead, because those builds lacked a working updater against the canonical repository (#25).

### Development

- Expanded regression coverage for Now Bar auto-start thresholds and release-note Markdown / irreversible-update policy (#25, #26).

**Full Changelog**: https://github.com/BenItBuhner/Codex-Meter/compare/v2.3.0...v2.3.1 <!-- pragma: allowlist secret -->

## 2.3.0 — 2026-07-14

### Fixed

- Rebuilt the Android 16 live usage notification on a dedicated default-importance channel, removed conflicting legacy Samsung metadata, and exposed actual Live Update promotion state for compatible Samsung Now Bar surfaces (#21).
- Improved reset-duration contrast on dashboard usage cards and hid misleading reset countdowns while a usage window remains completely unused, including home and lock-screen widgets (#23).
- Restored in-app update discovery and project links by pointing them at the canonical repository instead of a stale fork (#24).

### Development

- Centralized production GitHub release URLs and added regression guards that prevent the stale update source from being reintroduced (#24).

**Full Changelog**: https://github.com/BenItBuhner/Codex-Meter/compare/v2.2.0...v2.3.0 <!-- pragma: allowlist secret -->

## 2.2.0 — 2026-07-14

### Added

- Configurable reset-credit expiry reminders with multiple custom lead times, per-credit scheduling, reboot restoration, and automatic cancellation after sign-out or redemption (#15).
- A reminder action that opens the existing in-app confirmation before redeeming an expiring reset credit (#15).
- Secure in-app update discovery with daily checks, a dashboard update card, manual checks, and release history (#16).
- Verified APK installation that checks HTTPS trust, file size, SHA-256 integrity, package identity, version code, and signing certificate before handing an update to Android (#16).
- Upgrade recovery that preserves widget providers and options, then repairs existing widgets asynchronously after package replacement (#16).
- An optional live usage monitor showing real five-hour and weekly allowance values in Android 16 Live Updates and compatible Samsung Now Bar surfaces, with a standard notification fallback on earlier Android versions (#18).

### Changed

- The live usage monitor refreshes from new usage snapshots, restores after reboot or app replacement, marks unavailable windows clearly, and stops at the next valid reset or when dismissed, stopped, or signed out (#18).
- Reset-credit expiry reminders revalidate credit state immediately before notifying and coalesce duplicate scheduling state (#15).

### Fixed

- Dashboard usage-card labels now use theme-aware foreground colors and remain readable in dark mode (#19).
- Release-history toolbar and Android back navigation now return through the update flow correctly (#16).
- Expired five-hour usage windows no longer prevent the live monitor from falling back to a future weekly reset (#18).

### Development

- Added a debug-only local release fixture for end-to-end updater testing without weakening production update trust (#16).
- Expanded regression coverage for expiry-reminder planning, release parsing and integrity checks, widget upgrade repair, live-monitor lifecycle, and dark-mode usage labels (#15, #16, #18, #19).

### New Contributors

- @Robertg761 made their first contribution in https://github.com/BenItBuhner/Codex-Meter/pull/18. <!-- pragma: allowlist secret -->

**Full Changelog**: https://github.com/BenItBuhner/Codex-Meter/compare/v2.1.0...v2.2.0 <!-- pragma: allowlist secret -->

## 2.1.0 — 2026-07-13

### Added

- A resumable four-step One UI onboarding flow for first launch, including sign-in, skip, upgrade, and predictive-back handling (#13).
- A responsive light/dark OAuth completion page that returns users to the app after browser sign-in (#13).
- Optional notifications when five-hour or weekly allowances unexpectedly refill before their scheduled reset (#12).
- An independent notification preference for increases in available reset credits (#12).

### Changed

- Settings now uses an expanded, collapsible One UI toolbar that keeps primary controls within one-hand reach (#9).
- Local-data and privacy details now live in a dedicated Settings section instead of the dashboard (#10).
- Reset-credit observations are coalesced across usage and detail responses to prevent duplicate increase notifications (#12).

### Fixed

- Suppressed false refill celebrations after reset-credit redemption, including delayed backend propagation (#12).
- Retried credit notifications when Android previously blocked application- or channel-level delivery (#12).
- Cleaned up abandoned OAuth services when onboarding is skipped and hardened callback handling across Android versions (#13).

### Development

- Removed obsolete release binaries, recovery documents, generated caches, and superseded direct-build tools (#11).
- Documented the Android toolchain, package-authentication requirements, and release validation path for cloud builds (#8).

## 2.0.0 — 2026-07-12

### Added

- Samsung One UI-native dashboard, settings, widget configuration, and About surfaces.
- Responsive ring, four-dial, and battery-list home-screen widget layouts.
- Dedicated five-hour and weekly Samsung lock-screen widget providers.
- Updated adaptive icons, theme assets, previews, opacity controls, and usage wave presentation.
- Gradle wrapper and reproducible Android build configuration with vendored OneUI-Design metadata.

### Changed

- Reworked navigation, account controls, refresh preferences, and notification settings around SESL components.
- Updated widget rendering and sizing to follow the dimensions supplied by Android and One UI hosts.
- Simplified the release build script to use the Gradle Android plugin.

### Fixed

- Prevented the One UI pull-to-refresh indicator from crashing when its four-dot drawable starts.
- Added deduplicated low-usage alerts for both usage windows and notifications when reset-credit inventory increases.
- Decoupled reset-time reminders from the low-usage threshold and added an immediate notification test action.

## 1.7.0 — 2026-07-11

### Added

- Native lock-screen countdowns for the five-hour and weekly reset timestamps.
- Local countdown updates that do not require additional server requests.
- Reset-alert preferences for silent, notification-sound, and alarm-sound delivery.
- Per-limit alert selection and remaining-percentage thresholds.
- Alarm restoration after reboot and application replacement.

### Changed

- Revised Samsung-style lock-screen gauge geometry with a heavier stroke, smaller diameter, lower visual center, and more deliberate bottom gap.
- Reset alerts now trigger a background usage refresh and update all widget surfaces after the scheduled reset time.

### Security and behavior

- Notification permission is requested only when alerts are enabled on Android 13 or later.
- Exact-alarm capability is checked on supported Android versions; the scheduler falls back to an inexact idle-aware alarm when unavailable.
- Signing out cancels pending reset alerts.

## 1.6.0 — 2026-07-11

### Added

- Detailed Codex reset-credit inventory, expiration display, confirmation, and redemption.
- Automatic usage and reset-credit refresh after a successful redemption.
- Both-window, five-hour-only, and weekly-only widget configurations.
- Optional reset-credit count, expiration, and reset action on widgets.

### Changed

- Supersampled lock-screen ring and gauge geometry.
- Native Android text overlays and native bar progress elements for sharper Samsung lock-screen rendering.
- Single-measurement layouts now reclaim unused space.

## 1.5.0 — 2026-07-11

### Added

- Eight Samsung lock-screen/AOD picker entries: Numbers, Rings, Gauges, and Bars, each in Square (1×1) and Wide (2×1) sizes.
- Dedicated monochrome preview artwork and runtime renderers for every graphic lock-screen style.
- Size-aware lock-screen bitmap rendering using Samsung/Android host-provided `OPTION_APPWIDGET_SIZES` when available.

### Changed

- Preserved the existing square and wide numeric provider component names so installed 1.4.0 tiles remain valid after upgrading.
- Exposed graphic choices as separate provider components rather than relying on a lock-screen host configuration activity that One UI does not consistently surface.
- Extended the optional Samsung ServiceBox compatibility response to all eight style/size combinations.

### Verified

- Compiled and decoded all eight provider and companion metadata resources.
- Restricted lock-screen layouts to RemoteViews-safe `FrameLayout`, `TextView`, and `ImageView` classes.
- Signed with the same certificate as 1.2.0–1.4.0 for direct installation over the previous release.

## 1.4.0

- Replaced the legacy complication-marker provider with current One UI monotone AppWidget metadata and category routing.
- Added distinct 1x1 square and 2x1 wide lock-screen/AOD providers.
- Exported the compact providers so Samsung SystemUI can deliver update broadcasts.
- Reduced lock-screen layouts to RemoteViews-safe FrameLayout/TextView trees.
- Added Samsung companion metadata, the private 0x2000 widget category, and an optional ServiceBox RemoteViews response path.


## 1.3.0 — 2026-07-11

### Added

- Toggleable widget title, disabled by default for new and existing widget configurations.
- Samsung One UI compact lock-screen complication provider with standard keyguard fallback.
- Samsung One UI Home native frame/blur metadata, supported cell-size metadata, and size-specific picker previews.
- Samsung integration status and lock-screen setup guidance in Settings.

### Changed

- Reworked One UI 8+ app mode with a larger viewing-area header, Samsung-blue section labels, flat grouped cards, Galaxy-style spacing, and compact toolbar controls.
- Removed app-drawn corner rounding from Samsung One UI widget surfaces so the launcher can own the frame.
- Switched widget text to Samsung's `sec` family when available, with normal platform fallback elsewhere.
- Version 1.3.0 reuses the 1.2.0 signing certificate and supports in-place installation.

### Fixed

- Removed the default `CODEX` text from picker previews and initial widget layouts.
- Made successful snapshot persistence and refresh-error clearing atomic.
- Suppressed misleading red refresh warnings while a newly fetched snapshot is still current.
- Removed clipped toolbar buttons and inconsistent control minimum heights.

## 1.2.0 — 2026-07-10

### Added

- Automatic, Large, and Maximum ring/gauge sizing policies.
- Fully transparent widget backgrounds.
- Five- and ten-minute best-effort refresh choices using chained one-shot jobs.
- Separate Material expressive and One UI visual-language choices for the app and each widget.
- Large and maximum ring/dial layouts with orientation-aware launcher sizing.
- New progress-terminal adaptive icon and monochrome themed icon.

### Changed

- Reworked app cards, buttons, dropdowns, spacing, and corner radii into consistent shadow-free surface systems.
- Normalized widget bitmap density so graphics fill high-density screens correctly.
- Ring and dial resize updates now render one bitmap tier at a time to avoid Binder transaction overflows.
- Increased the widget provider's supported maximum dimensions.

### Fixed

- Undersized circular graphics on high-density devices.
- Graphic-tier selection that previously underestimated the widget's current portrait or landscape dimensions.
- Inconsistent overlapping shadows and radii in the application UI.

## 1.1.0 — 2026-07-10

### Fixed

- Added the missing `ACCESS_NETWORK_STATE` permission required by connectivity-constrained `JobScheduler` jobs.
- Prevented background scheduling errors from crashing OAuth completion, later launches, boot handling, or widget refreshes.
- Returned a successful browser callback immediately after encrypted credentials are committed.
- Preserved authenticated state when the initial usage request or post-login scheduling fails.
- Added a deep link and explicit browser button for returning to the application.
- Cleared stale OAuth state and hardened repeated-launch behavior.
- Corrected bar progress direction when displaying percentage used.

### Added

- Adaptive, bars, rings, dials, and minimal widget styles.
- Responsive Android 12+ `RemoteViews` layouts for multiple launcher sizes.
- Compact and comfortable density choices.
- Four additional accents and a monochrome option.
- Additional background transparency, reset-label, plan-label, updated-time, and refresh-button controls.
- In-application live widget previews.
- Original progress-knot adaptive and monochrome application icon.

## 1.0.0 — 2026-07-10

- Initial native Android application, OAuth login, Codex usage retrieval, and home-screen widget release.
