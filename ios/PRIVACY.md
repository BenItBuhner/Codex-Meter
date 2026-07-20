# Codex Meter Privacy Policy

Last updated: July 20, 2026

Codex Meter is designed so the iOS developer does not collect user data. The
app has no analytics, advertising, tracking, developer-operated account system,
or application relay server.

## Authentication and OpenAI services

When the user chooses to sign in, Codex Meter uses OpenAI’s device
authorization service. Authenticated requests travel directly between the
device and OpenAI to load Codex allowance, reset times, reset-credit inventory,
and to redeem a reset credit after confirmation.

OpenAI may process information under its own privacy policy and terms. Codex
Meter does not receive a copy on a developer-controlled server. See the
[OpenAI Privacy Policy](https://openai.com/policies/privacy-policy/).

## Information stored on the device

- OAuth access, refresh, and identity tokens are stored in Apple Keychain.
- The last successful usage response is cached locally for offline display.
- Notification and appearance preferences remain on the device.
- Widgets receive only percentages, reset dates, plan label, update time, and
  reset-credit count.

Widgets never receive OAuth credentials, account identifiers, or reset-credit
IDs.

## Notifications and diagnostics

Notifications are scheduled locally after permission is granted. Codex Meter
does not include a crash-reporting or telemetry SDK and does not transmit app
interaction, advertising, or diagnostic data to the developer.

## Retention, deletion, and choices

Local cache data remains until it is replaced or the user signs out. Choosing
Sign Out removes credentials, cached account data, pending background work, and
scheduled notifications from the device. The app also attempts to revoke the
OpenAI refresh token; local deletion succeeds even when the network is
unavailable.

Demo mode is entirely local and does not contact OpenAI. Notification
permission is optional.

## Children, changes, and contact

Codex Meter is not directed to children and does not knowingly collect personal
information from children. Material changes will be posted with a revised date.

For privacy or support questions, contact
[Filip Bukovina](https://github.com/FBukovina) through GitHub.

Codex Meter is unofficial and is not affiliated with or endorsed by OpenAI.
