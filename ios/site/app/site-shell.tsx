import Link from "next/link";

export function SiteHeader() {
  return (
    <header className="site-header">
      <div className="wrap header-inner">
        <Link className="brand" href="/" aria-label="Codex Meter home">
          <span className="brand-mark" aria-hidden="true">CM</span>
          <span>Codex Meter</span>
        </Link>
        <nav aria-label="Primary navigation">
          <Link href="/#features">Features</Link>
          <Link href="/privacy">Privacy</Link>
          <Link href="/eula">EULA</Link>
          <Link href="/app-store">App Store</Link>
        </nav>
      </div>
    </header>
  );
}

export function SiteFooter() {
  return (
    <footer className="site-footer">
      <div className="wrap footer-inner">
        <div>
          <strong>Codex Meter</strong>
          <p>An unofficial native client. Not affiliated with or endorsed by OpenAI.</p>
        </div>
        <div className="footer-links">
          <Link href="/privacy">Privacy Policy</Link>
          <Link href="/eula">EULA</Link>
          <a href="https://github.com/BenItBuhner/Codex-Meter">Source project</a>
          <a href="https://github.com/FBukovina">iOS developer</a>
        </div>
      </div>
    </footer>
  );
}

export function LegalPage({
  eyebrow,
  title,
  updated,
  children,
}: {
  eyebrow: string;
  title: string;
  updated: string;
  children: React.ReactNode;
}) {
  return (
    <div className="site-frame">
      <SiteHeader />
      <main className="legal-wrap wrap">
        <div className="eyebrow">{eyebrow}</div>
        <h1>{title}</h1>
        <p className="updated">Last updated {updated}</p>
        <article className="legal-content">{children}</article>
      </main>
      <SiteFooter />
    </div>
  );
}
