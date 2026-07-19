# Release checklist

## Completed in the repository

- Swift 6 app, phone/watch widget extensions, local core package, unit tests, and UI tests.
- iPhone and iPad adaptive layouts with a visible offline demo path.
- App Group snapshot isolation, Keychain-only credentials, privacy manifest,
  privacy policy, MIT notice, and App Review demo instructions.
- Network tests use injected URL protocols and never contact OpenAI.
- Credential-free settings transfer and sanitized, versioned WatchConnectivity payloads.
- iOS 18 / watchOS 11 deployment targets with Xcode 26 CI.

## Required before TestFlight or App Store submission

- Confirm written permission and acceptable use for OpenAI OAuth, the Codex
  client identifier, private ChatGPT account routes, trademarks, and branding.
- Configure the App Group, ActivityKit, WatchConnectivity, background-task, and widget capabilities for all App IDs in
  the Apple Developer portal, then archive with the distribution team.
- Review the generated Xcode Privacy Report and enter matching App Store privacy
  answers and a public privacy-policy URL.
- Test device-code authentication, Keychain persistence, rotated refresh tokens,
  ordinary local notifications after termination, background refresh, and every
  WidgetKit family on a physical iPhone and iPad.
- Test 40/42/46 mm and Ultra watch layouts, complications, Smart Stack, reachable/unreachable phone states, and background snapshot delivery.
- Upload an internal TestFlight build and complete archive validation. The user
  accepts Apple agreements and performs the final **Submit for Review** action.
- With the account owner explicitly authorizing the irreversible action, redeem
  exactly one real reset credit and verify both usage windows and inventory
  refresh afterward.
- Verify the private endpoints and device-code contract immediately before each
  release; they are not stable public API surface.
