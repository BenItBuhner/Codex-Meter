import { LegalPage } from "../site-shell";

export default function AppStorePage() {
  return (
    <LegalPage eyebrow="App Store copy" title="Codex Meter for iOS" updated="July 20, 2026">
      <h2>Subtitle</h2>
      <p className="store-copy">Codex usage at a glance</p>

      <h2>Promotional text</h2>
      <p className="store-copy">
        See your five-hour and weekly Codex allowance, reset times, and earned reset
        credits from a native app and WidgetKit widgets.
      </p>

      <h2>Description</h2>
      <div className="store-copy">
        <p>
          Codex Meter puts your Codex allowance where it is easy to check—on your
          iPhone, iPad, and Home Screen.
        </p>
        <p>
          Sign in with your existing OpenAI account to see your current five-hour and
          weekly usage windows, upcoming reset times, plan status, and earned reset
          credits. Clear visual meters make the important numbers easy to understand
          without repeatedly opening a browser.
        </p>
        <p>FEATURES</p>
        <ul>
          <li>Five-hour and weekly Codex allowance meters</li>
          <li>Locally updating reset countdowns</li>
          <li>Earned reset-credit inventory and expiry information</li>
          <li>Confirmation before reset-credit redemption</li>
          <li>Home Screen and accessory widgets</li>
          <li>Optional refill, credit increase, and credit expiry notifications</li>
          <li>Background refresh and offline display of the latest successful update</li>
          <li>Offline demo mode for exploring the interface without signing in</li>
        </ul>
        <p>PRIVATE BY DESIGN</p>
        <p>
          Codex Meter has no analytics, advertising, developer-operated account system,
          or relay server. Credentials stay in Apple Keychain, and authenticated requests
          go directly from your device to OpenAI.
        </p>
        <p>
          Codex Meter is an independent, unofficial client and is not affiliated with
          or endorsed by OpenAI. ChatGPT and Codex are trademarks of their respective owners.
        </p>
      </div>

      <h2>Keywords</h2>
      <p className="store-copy">codex,usage,allowance,meter,reset,credits,widgets,developer,productivity</p>

      <h2>App Privacy answer</h2>
      <p className="store-copy"><strong>No, we do not collect data from this app.</strong></p>
      <p>
        This answer reflects the submitted binary: there is no developer server,
        analytics, advertising, tracking, or telemetry SDK. Requests made to OpenAI
        service the user&apos;s direct account request and are described in the Privacy Policy.
      </p>
    </LegalPage>
  );
}
