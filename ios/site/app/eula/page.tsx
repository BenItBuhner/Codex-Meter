import { LegalPage } from "../site-shell";

export default function EulaPage() {
  return (
    <LegalPage eyebrow="Legal" title="End User License Agreement" updated="July 20, 2026">
      <p>
        Codex Meter is licensed through Apple&apos;s App Store under the
        {" "}<a href="https://www.apple.com/legal/internet-services/itunes/dev/stdeula/">Apple Standard End User License Agreement</a>.
        That agreement governs your license to use the app. This page provides
        app-specific notices and does not replace or modify Apple&apos;s Standard EULA.
      </p>

      <h2>Unofficial client</h2>
      <p>
        Codex Meter is an independent, unofficial client and is not affiliated with,
        sponsored by, or endorsed by OpenAI. OpenAI, ChatGPT, and Codex are trademarks
        of their respective owners.
      </p>

      <h2>External services</h2>
      <p>
        The app connects directly to OpenAI services using your authorization. Your
        use of those services remains subject to OpenAI&apos;s terms and policies. Private
        or undocumented service routes may change, become unavailable, or require the
        app to be updated.
      </p>

      <h2>Reset-credit actions</h2>
      <p>
        Redeeming a reset credit changes your OpenAI account and may be irreversible.
        Codex Meter displays a confirmation before sending a redemption request. You
        are responsible for reviewing that confirmation and the resulting account state.
      </p>

      <h2>Availability and warranty</h2>
      <p>
        Usage values and reset times are informational and depend on data supplied by
        OpenAI. The app and related site are provided on an “as is” and “as available”
        basis to the extent permitted by applicable law. Service interruptions,
        delayed background refresh, or changes to external services may affect accuracy.
      </p>

      <h2>Open-source components</h2>
      <p>
        Open-source notices and applicable licenses are available inside the app. The
        original Codex Meter project is made available under the MIT License.
      </p>

      <h2>Support</h2>
      <p>
        For questions about the iOS app, contact the developer through
        {" "}<a href="https://github.com/FBukovina">GitHub</a>.
      </p>
    </LegalPage>
  );
}
