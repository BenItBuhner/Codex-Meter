package dev.bennett.codexmeter;

/** Generates the small localhost page shown after the browser OAuth callback. */
public final class OAuthBrowserPage {
    private OAuthBrowserPage() {
    }

    public static String render(String message, boolean success, String appLink) {
        String safeMessage = htmlEscape(message);
        String safeLink = htmlEscape(appLink);
        String scriptLink = javascriptString(appLink);
        String title = success ? "You’re connected" : "Let’s try that again";
        String eyebrow = success ? "SIGN-IN COMPLETE" : "SIGN-IN NEEDS ATTENTION";
        String action = success ? "Open Codex Meter" : "Back to Codex Meter";
        String symbol = success ? "&#10003;" : "!";
        String autoReturn = success
                ? "<script>setTimeout(function(){window.location.href='" + scriptLink
                    + "';},700);</script>"
                : "";
        return "<!doctype html><html lang=\"en\"><head>"
                + "<meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<meta name=\"color-scheme\" content=\"light dark\"><title>Codex Meter</title>"
                + "<style>"
                + ":root{color-scheme:light dark;--bg:#f1f1f3;--card:#fcfcff;--text:#000;--sub:#747477;"
                + "--accent:#0381fe;--on:#fff;--soft:#e8efff;--ring:#d9d9de}"
                + "*{box-sizing:border-box}body{margin:0;min-height:100vh;background:var(--bg);color:var(--text);"
                + "font-family:SamsungOne,'Samsung Sans',system-ui,-apple-system,sans-serif;display:grid;"
                + "place-items:center;padding:24px}.card{width:min(440px,100%);background:var(--card);"
                + "border-radius:28px;padding:30px 26px 26px;box-shadow:0 8px 30px rgba(0,0,0,.06)}"
                + ".mark{display:grid;place-items:center;width:58px;height:58px;border-radius:50%;"
                + "background:var(--soft);color:var(--accent);font-size:29px;font-weight:700;margin-bottom:28px}"
                + ".eyebrow{color:var(--accent);font-size:12px;font-weight:700;letter-spacing:.08em;margin:0 0 9px}"
                + "h1{font-size:30px;line-height:1.16;letter-spacing:-.025em;margin:0 0 12px}"
                + "p{font-size:16px;line-height:1.5;color:var(--sub);margin:0 0 28px}"
                + ".button{display:block;width:100%;padding:16px 20px;border-radius:999px;background:var(--accent);"
                + "color:var(--on);text-align:center;text-decoration:none;font-size:18px;font-weight:700}"
                + ".hint{font-size:12px;line-height:1.4;color:var(--sub);text-align:center;margin:16px 0 0}"
                + "@media(prefers-color-scheme:dark){:root{--bg:#050608;--card:#16181c;--text:#f8f8fa;"
                + "--sub:#b7bac2;--accent:#5ca9ff;--on:#07182a;--soft:#1c3552;--ring:#373a40}"
                + ".card{box-shadow:none}}"
                + "@media(prefers-reduced-motion:no-preference){.card{animation:in .28s ease-out both}"
                + "@keyframes in{from{opacity:0;transform:translateY(8px)}to{opacity:1;transform:none}}}"
                + "</style></head><body><main class=\"card\">"
                + "<div class=\"mark\" aria-hidden=\"true\">" + symbol + "</div>"
                + "<div class=\"eyebrow\">" + eyebrow + "</div><h1>" + title + "</h1>"
                + "<p>" + safeMessage + "</p><a class=\"button\" href=\"" + safeLink + "\">"
                + action + "</a><div class=\"hint\">"
                + (success ? "Returning to the app automatically…" : "Return to the app to restart secure sign-in.")
                + "</div></main>" + autoReturn + "</body></html>";
    }

    static String htmlEscape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    static String javascriptString(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("'", "\\'")
                .replace("\r", "\\r").replace("\n", "\\n")
                .replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
    }
}
