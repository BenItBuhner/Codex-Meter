# Changelog

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
