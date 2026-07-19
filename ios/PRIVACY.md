# Codex Meter Privacy Policy

Last updated: July 19, 2026

Codex Meter does not operate an account system, analytics service, advertising
network, or application relay server.

When you choose to sign in, the app opens an OpenAI-controlled authentication
page and exchanges the resulting device authorization directly with OpenAI.
OAuth access, refresh, and identity tokens are stored in the Apple Keychain on
your device and are not included in backups or synchronized through iCloud.

The app sends authenticated requests directly to OpenAI and ChatGPT only to:

- load your Codex allowance and reset times;
- load your earned reset-credit inventory; and
- redeem a reset credit after you explicitly confirm the action.

The app stores the last successful response locally so information remains
visible offline. Its widget extensions and Apple Watch companion receive only a sanitized snapshot with
percentages, reset dates, plan label, update time, and reset-credit count. It
may also contain the next reset-credit expiry and connection freshness. These
surfaces never receive OAuth credentials, account identifiers, email, or reset-credit IDs.

Settings export is limited to appearance, refresh, usage-warning, notification,
and Live Activity preferences. Export files never contain authentication,
identity, cached usage, or Keychain data.

You can remove all locally stored credentials and cached account data by using
Sign Out in Settings. The app also attempts to revoke the refresh token with
OpenAI, but local deletion succeeds even if the network is unavailable.

Demo mode is entirely local and does not contact OpenAI.

Codex Meter is unofficial and is not affiliated with or endorsed by OpenAI.
