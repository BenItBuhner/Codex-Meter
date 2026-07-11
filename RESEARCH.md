# Codex Meter implementation research

Reviewed: 2026-07-11

## Authentication flow

The app follows the browser-based ChatGPT login flow used by the open-source Codex client and compatible third-party clients:

- OAuth 2.0 authorization code flow with PKCE (`S256`) and a cryptographically random `state` value.
- OpenAI authorization issuer: `https://auth.openai.com`.
- Public Codex OAuth client identifier: `app_EMoamEEZ73f0CkXaXp7hrann`.
- Loopback callback: `http://localhost:1455/auth/callback`, with port 1457 as the Codex allow-listed fallback.
- Scope: `openid profile email offline_access`.
- Browser-hosted password and consent pages; the Android app does not embed a WebView or receive the password.
- Authorization-code exchange is form encoded. Current first-party Codex token refresh and token revocation requests are JSON encoded.
- The ChatGPT account identifier is recovered from the ID/access-token claims, preferring `chatgpt_account_id`, then the nested `https://api.openai.com/auth.chatgpt_account_id`, then the first organization identifier.

The foreground sign-in service keeps the localhost callback alive while the browser is open. Access, refresh, and ID tokens are encrypted at rest with an Android Keystore AES-GCM key. Android backup and device-transfer extraction are disabled.

## Usage data

The current open-source Codex backend client selects the ChatGPT route:

`GET https://chatgpt.com/backend-api/wham/usage`

The app sends:

- `Authorization: Bearer <access token>`
- `ChatGPT-Account-Id: <account id>` when the claim is available
- an app-specific `originator` and `User-Agent`

The response schema exposes a top-level `rate_limit` with `primary_window` and `secondary_window`. Each window contains:

- `used_percent`
- `limit_window_seconds`
- `reset_after_seconds`
- `reset_at` (Unix epoch seconds)

Codex Meter identifies the short and long windows by duration rather than trusting their order. It prefers the top-level Codex allowance and uses `additional_rate_limits` only to fill a missing window. Values are clamped to 0–100%, malformed/zero-duration windows are ignored, and the last successful response is cached for offline widget rendering.

OpenAI describes Codex allowance as a rolling five-hour limit, with weekly limits also applying depending on plan and usage. The exact allowance varies by plan and workload, so the widget displays percentages rather than inventing absolute message counts.

## Android widget architecture

- Native `AppWidgetProvider` + `RemoteViews`; no browser wrapper.
- Adaptive bars, circular rings, gauge dials, and minimal layouts selected explicitly or from launcher-reported widget dimensions.
- Android 12+ responsive `RemoteViews` mappings provide distinct small, medium, and large layouts without requiring separate widget providers.
- Android 12+ preview layout, responsive size metadata, and target-cell metadata.
- Android 9+ `reconfigurable` widget feature; Android 12+ `configuration_optional` support.
- Launcher configuration activity for first placement and long-press reconfiguration where the launcher exposes it.
- Immediate in-app refresh, tap-requested widget refresh, periodic `JobScheduler` refresh, and a one-off job just after the next reset. All scheduling entry points catch platform/runtime failures so a scheduler problem cannot invalidate OAuth or terminate the application.
- The selectable cadence includes 5 and 10 minutes through chained one-shot jobs, while 15 minutes and longer use periodic jobs. Android may defer jobs for battery, network, Doze, or vendor policy; continuous second-by-second polling is neither reliable nor appropriate for an Android home-screen widget.

## Version 1.1 crash analysis

The first release requested `JobInfo.NETWORK_TYPE_ANY` but omitted `android.permission.ACCESS_NETWORK_STATE`. Android 16 throws a `SecurityException` when a connectivity-constrained job is scheduled without that permission. The exception occurred after the OAuth token exchange and secure write had succeeded, so the old callback page incorrectly presented a sign-in failure even though the account had been connected. The same scheduling call during later activity startup caused the observed repeat-launch failure.

Version 1.1 adds the permission and separates authentication commit from post-authentication work: the callback receives a success response immediately after encrypted token storage, while initial usage loading, periodic scheduling, reset scheduling, and widget rendering execute as recoverable follow-up operations.

## API stability boundary

The OAuth behavior and usage route are present in current OpenAI-owned Codex source, and the same public OAuth client is used by compatible third-party tooling. The `wham/usage` route is not documented as a separately versioned public Android SDK/API contract. OpenAI can change eligibility, OAuth policy, endpoint paths, headers, or response fields. The app reports failures without deleting the last good widget value, but a future server change may require an update.

## Primary references

OpenAI documentation:

- https://developers.openai.com/codex/auth
- https://developers.openai.com/codex/pricing

OpenAI Codex source:

- https://github.com/openai/codex/blob/main/codex-rs/login/src/server.rs
- https://github.com/openai/codex/blob/main/codex-rs/login/src/auth/manager.rs
- https://github.com/openai/codex/blob/main/codex-rs/login/src/auth/revoke.rs
- https://github.com/openai/codex/blob/main/codex-rs/backend-client/src/client/rate_limit_resets.rs
- https://github.com/openai/codex/blob/main/codex-rs/codex-backend-openapi-models/src/models/rate_limit_status_details.rs
- https://github.com/openai/codex/blob/main/codex-rs/codex-backend-openapi-models/src/models/rate_limit_window_snapshot.rs

OpenCode implementation reviewed for third-party interoperability:

- https://github.com/anomalyco/opencode/blob/dev/packages/opencode/src/plugin/openai/codex.ts

Android documentation:

- https://developer.android.com/develop/ui/views/appwidgets/configuration
- https://developer.android.com/develop/ui/views/appwidgets/advanced
- https://developer.android.com/reference/android/app/job/JobInfo.Builder

## Samsung One UI widget integration (version 1.3)

Two separate Samsung-private behaviors were evaluated and kept isolated from the standard Android widget path.

### Compact lock-screen complication

The first public One UI 8 implementation used the receiver marker `widgetStyle=complication` with raw numeric provider attributes. A newer public proof of concept, documented in June 2026, uses the current Samsung monotone contract instead: category `0x2000`, `targetHost=lock_and_aod|cover`, `widgetStyle=monotone`, size-specific initial and preview layouts, and a `samsung.appwidget.monotone.info` companion resource. It registers separate 56×56 dp (tiny/1x1) and 124×56 dp (small|medium/2x1) providers and exports those receivers so SystemUI can deliver update broadcasts.

Codex Meter 1.4 follows that newer contract exactly with dedicated square and wide providers. Both use classic, static `RemoteViews` layouts containing only a `FrameLayout` and `TextView`, monochrome content, no account identifier, and no privileged Samsung permission. It also supplies the optional package-scoped ServiceBox RemoteViews response path without requesting the obsolete `FACE_WIDGET` permission.

Reference implementation reviewed:

- https://github.com/morphatic/wheresthemoon
- `app/src/main/AndroidManifest.xml`
- `app/src/main/res/values/attrs.xml`
- `app/src/main/res/xml/wtm_aspect_info.xml`

### One UI Home native frame and blur

The user-supplied OneDoist APK was decoded and inspected locally. Its working One UI provider contained:

- application metadata `SamsungBasicInteraction=SEP10`;
- receiver metadata `com.sec.android.appwidget.widgetinfo` pointing to a supported-cell-size descriptor;
- private provider attributes `widgetStyle=colorful` and a bitset of supported `widgetSize` values;
- per-size private preview layout attributes;
- a root view identified as `@android:id/background` with a translucent fill and no app-defined corner radius.

Codex Meter mirrors those registration and layout conventions for the normal home widget. When the user selects One UI visual language on a Samsung device, the root uses a plain translucent rectangle so One UI Home can own clipping, corner geometry, and blur. Material mode and non-Samsung devices retain ordinary app-rendered rounded backgrounds. Fully transparent mode supplies an alpha-zero root.

These are private, reverse-engineered host conventions. No public Samsung SDK or compatibility guarantee was found. They are therefore additive metadata, while the normal Android AppWidgetProvider, `RemoteViews`, responsive sizes, and keyguard category remain the supported fallback.

## Refresh-error state model (version 1.3)

The old UI displayed any stored request failure next to the newest snapshot, even if the snapshot had just been refreshed successfully. That could occur when a follow-up/background request failed after a successful manual update. Version 1.3 timestamps failures, clears failure state in the same committed preference transaction as a successful snapshot, and only presents a red account warning when no snapshot exists or the cached result is at least 15 minutes old. Scheduler failures remain separate from usage-request failures.


## Lock-screen visual variants (version 1.5)

A standard Android AppWidget can declare a configuration activity, but the host is responsible for launching that activity. Samsung's compact private lock/AOD host successfully binds monotone providers yet does not consistently surface the normal configuration flow used by home-screen launchers. Version 1.5 therefore registers each visual choice as a distinct provider component.

The package now has four styles in both Samsung-observed dimensions:

- Numbers: platform `TextView` content.
- Rings: two full circular progress indicators rendered as a monochrome bitmap.
- Gauges: two open-arc dial indicators rendered as a monochrome bitmap.
- Bars: two labeled horizontal progress tracks rendered as a monochrome bitmap.

All graphic layouts contain only a `FrameLayout` and `ImageView`, while numeric layouts contain only a `FrameLayout` and `TextView`. Runtime images are generated at the size supplied through `OPTION_APPWIDGET_SIZES` when available, with 56×56 and 124×56 fallbacks. The original numeric square and wide component names remain unchanged to avoid invalidating existing 1.4 placements.
