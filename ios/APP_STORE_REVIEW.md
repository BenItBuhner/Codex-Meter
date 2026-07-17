# App Review Notes

Codex Meter is a client for the user's existing ChatGPT/Codex account. It does
not create a separate Codex Meter account, sell digital goods, or include
analytics or advertising.

## Review without credentials

1. Launch the app while signed out.
2. Tap **Explore demo**.
3. Pull to refresh the dashboard, inspect both usage windows, open Settings,
   test the reset confirmation flow, and configure the Codex Meter widget.
4. Tap **Leave Demo** in Settings to return to the signed-out screen.

Demo mode is visible to all users and performs no network requests.

## Live sign-in

Live sign-in uses OpenAI's Codex device-code flow. The app displays the one-time
code, opens `https://auth.openai.com/codex/device`, and polls OpenAI until the
user completes or cancels authentication. OAuth credentials are stored only in
the device Keychain.

The app clearly identifies itself as unofficial and links to its privacy policy,
source attribution, and credential removal controls.
