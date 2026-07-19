# Changelog

## 2.4.2 — 2026-07-19

### Added

- Off option for accelerated-usage warning sensitivity so pace estimates can stay on without orange warnings or matching Now Bar auto-start triggers (#57).
- Reset-credit expiration details on the dashboard and inventory, with soonest-expiring credit selection when redeeming (#56).

### Changed

- Android Settings now uses a compact hub with focused destinations for appearance, refresh and usage, notifications, Now Bar, updates, transfer, and privacy, revealing nested options only when their parent feature is enabled (#54).

### Fixed

- Onboarding card spacing and Settings bottom padding no longer collapse against adjacent cards or the screen edge (#52).

**Full Changelog**: https://github.com/[REDACTED]/Codex-Meter/compare/v2.4.1...v2.4.2 <!-- pragma: allowlist secret -->

## 2.4.1 — 2026-07-18

### Fixed

- Active Android Live Updates now rebuild immediately after promoted-notification access changes, allowing automatic Samsung fallback and explicit Android modes to adopt the newly available notification contract without waiting for another usage refresh.
- Live Update construction now adapts to both early Android 16's required colorization and newer uncolorized promotion rules, while notification and Now Bar Codex icons use SystemUI-compatible vector syntax.

**Full Changelog**: https://github.com/[REDACTED]/Codex-Meter/compare/v2.4.0...v2.4.1 <!-- pragma: allowlist secret -->

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

**Full Changelog**: https://github.com/[REDACTED]/Codex-Meter/compare/v2.3.3...v2.4.0 <!-- pragma: allowlist secret -->

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

**Full Changelog**: https://github.com/[REDACTED]/Codex-Meter/compare/v2.3.1...v2.3.2 <!-- pragma: allowlist secret -->

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
