import { LegalPage } from "../site-shell";

export default function PrivacyPage() {
  return (
    <LegalPage eyebrow="Legal" title="Privacy Policy" updated="July 20, 2026">
      <p>
        Codex Meter is designed so the iOS developer does not collect your data.
        The app has no analytics, advertising, tracking, developer-operated account
        system, or application relay server.
      </p>

      <h2>Authentication and OpenAI services</h2>
      <p>
        When you choose to sign in, Codex Meter uses OpenAI&apos;s device authorization
        service. Authenticated requests travel directly between your device and
        OpenAI to load Codex allowance, reset times, reset-credit inventory, and to
        redeem a reset credit after your confirmation. OpenAI may process information
        under its own privacy policy and terms; Codex Meter does not receive a copy on
        a developer-controlled server.
      </p>
      <p>
        Learn more in the <a href="https://openai.com/policies/privacy-policy/">OpenAI Privacy Policy</a>.
      </p>

      <h2>Information stored on your device</h2>
      <ul>
        <li>OAuth access, refresh, and identity tokens are stored in Apple Keychain.</li>
        <li>The last successful usage response is cached locally for offline display.</li>
        <li>Your notification and appearance preferences remain on the device.</li>
        <li>Widgets receive only percentages, reset dates, plan label, update time, and reset-credit count.</li>
      </ul>
      <p>Widgets never receive OAuth credentials, account identifiers, or reset-credit IDs.</p>

      <h2>Notifications and diagnostics</h2>
      <p>
        Notifications are scheduled locally after you grant permission. Codex Meter
        does not include a crash-reporting or telemetry SDK and does not transmit app
        interaction, advertising, or diagnostic data to the developer.
      </p>

      <h2>Retention, deletion, and your choices</h2>
      <p>
        Local cache data remains until it is replaced or you sign out. Choosing Sign
        Out removes credentials, cached account data, pending background work, and
        scheduled notifications from the device. The app also attempts to revoke the
        OpenAI refresh token; local deletion succeeds even when the network is unavailable.
      </p>
      <p>
        Demo mode is entirely local and does not contact OpenAI. You can decline
        notification permission and continue using the main app.
      </p>

      <h2>Children</h2>
      <p>Codex Meter is not directed to children and does not knowingly collect personal information from children.</p>

      <h2>Changes and contact</h2>
      <p>
        Material changes will be posted on this page with a revised date. For privacy
        or support questions, contact the iOS developer through
        {" "}<a href="https://github.com/FBukovina">GitHub</a>.
      </p>

      <p className="notice">Codex Meter is unofficial and is not affiliated with or endorsed by OpenAI.</p>
    </LegalPage>
  );
}
