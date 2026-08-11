import Link from "next/link";
import { SiteFooter, SiteHeader } from "./site-shell";

const features = [
  ["Five-hour window", "See current Codex usage and the exact reset time at a glance."],
  ["Weekly allowance", "Keep the longer usage window visible without opening a browser."],
  ["Reset credits", "Review earned credits and receive a clear confirmation before redemption."],
  ["Home Screen widgets", "Check allowances and reset times from supported WidgetKit sizes."],
  ["Helpful alerts", "Choose refill, credit increase, and credit expiry reminders."],
  ["Private by design", "No analytics, advertising, developer account system, or relay server."],
] as const;

export default function Home() {
  return (
    <div className="site-frame">
      <SiteHeader />
      <main>
        <section className="hero wrap">
          <div className="eyebrow">Codex usage, at a glance</div>
          <h1>Know what is left.<br />Know when it resets.</h1>
          <p className="hero-copy">
            Codex Meter is an unofficial native iPhone and iPad client for
            checking Codex allowance, reset times, and earned reset credits.
          </p>
          <div className="hero-actions">
            <Link className="button primary" href="/app-store">Read the App Store description</Link>
            <Link className="button secondary" href="/privacy">Privacy first</Link>
          </div>
          <div className="meter-panel" aria-label="Decorative Codex usage preview">
            <div className="meter-copy">
              <span>Five-hour allowance</span>
              <strong>68%</strong>
            </div>
            <div className="meter-track"><span /></div>
            <div className="meter-meta"><span>32% used</span><span>Resets in 2h 14m</span></div>
          </div>
        </section>

        <section className="section wrap" id="features">
          <div className="section-heading">
            <div className="eyebrow">Built for Apple platforms</div>
            <h2>Your allowance, without the guesswork.</h2>
          </div>
          <div className="feature-grid">
            {features.map(([title, copy], index) => (
              <article className="feature-card" key={title}>
                <span className="feature-number">0{index + 1}</span>
                <h3>{title}</h3>
                <p>{copy}</p>
              </article>
            ))}
          </div>
        </section>

        <section className="privacy-callout wrap">
          <div>
            <div className="eyebrow">No developer data collection</div>
            <h2>Your account stays between your device and OpenAI.</h2>
          </div>
          <p>
            OAuth credentials remain in Apple Keychain. Widgets receive a
            sanitized usage snapshot and never receive tokens or account IDs.
            Signing out removes credentials and cached account data from the device.
          </p>
          <Link className="text-link" href="/privacy">Read the full privacy policy →</Link>
        </section>
      </main>
      <SiteFooter />
    </div>
  );
}
