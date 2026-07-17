# Release checklist

## Completed in the repository

- Swift 6 app, widget extension, local core package, unit tests, and UI tests.
- iPhone and iPad adaptive layouts with a visible offline demo path.
- App Group snapshot isolation, Keychain-only credentials, privacy manifest,
  privacy policy, MIT notice, and App Review demo instructions.
- Network tests use injected URL protocols and never contact OpenAI.

## Required before TestFlight or App Store submission

- Confirm written permission and acceptable use for OpenAI OAuth, the Codex
  client identifier, private ChatGPT account routes, trademarks, and branding.
- Configure the App Group and background-task entitlement for both App IDs in
  the Apple Developer portal, then archive with the distribution team.
- Review the generated Xcode Privacy Report and enter matching App Store privacy
  answers and a public privacy-policy URL.
- Test device-code authentication, Keychain persistence, rotated refresh tokens,
  ordinary local notifications after termination, background refresh, and every
  WidgetKit family on a physical iPhone and iPad.
- With the account owner explicitly authorizing the irreversible action, redeem
  exactly one real reset credit and verify both usage windows and inventory
  refresh afterward.
- Verify the private endpoints and device-code contract immediately before each
  release; they are not stable public API surface.

