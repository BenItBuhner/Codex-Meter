import assert from "node:assert/strict";
import { access, readFile } from "node:fs/promises";
import test from "node:test";

const projectRoot = new URL("../", import.meta.url);

async function render(path = "/") {
  const workerUrl = new URL("../dist/server/index.js", import.meta.url);
  workerUrl.searchParams.set("test", `${process.pid}-${Date.now()}`);
  const { default: worker } = await import(workerUrl.href);

  return worker.fetch(
    new Request(`https://codex-meter.example${path}`, {
      headers: { accept: "text/html", host: "codex-meter.example" },
    }),
    { ASSETS: { fetch: async () => new Response("Not found", { status: 404 }) } },
    { waitUntil() {}, passThroughOnException() {} },
  );
}

test("server-renders the Codex Meter landing page and metadata", async () => {
  const response = await render();
  assert.equal(response.status, 200);
  assert.match(response.headers.get("content-type") ?? "", /^text\/html\b/i);

  const html = await response.text();
  assert.match(html, /Codex Meter for iOS/);
  assert.match(html, /Know what is left/);
  assert.match(html, /No developer data collection/);
  assert.match(html, /https:\/\/codex-meter\.example\/og\.png/);
  assert.doesNotMatch(html, /Your site is taking shape|codex-preview|react-loading-skeleton/);
});

test("publishes privacy, EULA, and App Store copy routes", async () => {
  const [privacy, eula, appStore] = await Promise.all([
    render("/privacy").then((response) => response.text()),
    render("/eula").then((response) => response.text()),
    render("/app-store").then((response) => response.text()),
  ]);

  assert.match(privacy, /Privacy Policy/);
  assert.match(privacy, /does not collect your data/);
  assert.match(privacy, /Retention, deletion, and your choices/);
  assert.match(eula, /Apple Standard End User License Agreement/);
  assert.match(eula, /does not replace or modify Apple/);
  assert.match(appStore, /Codex usage at a glance/);
  assert.match(appStore, /No, we do not collect data from this app/);
});

test("removes starter-only assets and keeps the social card", async () => {
  const packageJson = await readFile(new URL("../package.json", import.meta.url), "utf8");
  assert.doesNotMatch(packageJson, /react-loading-skeleton/);
  await assert.rejects(access(new URL("../app/_sites-preview", import.meta.url)));
  await access(new URL("public/og.png", projectRoot));
});
