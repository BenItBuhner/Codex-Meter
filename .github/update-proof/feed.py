"""A loopback stand-in for GitHub's releases API, used by desktop-update-proof.yml.

Serves one prerelease (the "newer" build) the way api.github.com does:
  GET /releases?per_page=N  -> a JSON array with the release
  GET /releases/latest      -> 404 (no stable release, like an alpha-only feed)
  GET /assets/<name>        -> the release files from the assets folder

With --tamper-first, the first download of the installer is served with one byte flipped, so the
proof shows the app refusing a file that doesn't match SHA256SUMS before the honest retry.
"""
import argparse
import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

parser = argparse.ArgumentParser()
parser.add_argument("--port", type=int, default=8765)
parser.add_argument("--assets", required=True)
parser.add_argument("--version", required=True)
parser.add_argument("--tamper-first", default="")
args = parser.parse_args()
base = f"http://127.0.0.1:{args.port}"
tampered = set()


def release():
    files = sorted(os.listdir(args.assets))
    return {
        "tag_name": "v" + args.version,
        "name": f"Codex Meter {args.version}",
        "draft": False,
        "prerelease": True,
        "published_at": "2026-11-14T12:00:00Z",
        "html_url": f"{base}/releases/tag/v{args.version}",
        "body": "Update-flow proof build.\n\n- Desktop in-app updates\n- Priority page, value estimates, usage is back",
        "assets": [
            {"name": n, "size": os.path.getsize(os.path.join(args.assets, n)), "browser_download_url": f"{base}/assets/{n}"}
            for n in files
        ],
    }


class Handler(BaseHTTPRequestHandler):
    def send(self, status, body, kind="application/json"):
        self.send_response(status)
        self.send_header("Content-Type", kind)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        path = self.path.split("?", 1)[0]
        if path == "/releases":
            return self.send(200, json.dumps([release()]).encode())
        if path == "/releases/latest":
            return self.send(404, b'{"message":"Not Found"}')
        if path.startswith("/assets/"):
            name = os.path.basename(path)
            full = os.path.join(args.assets, name)
            if not os.path.isfile(full):
                return self.send(404, b"missing")
            data = bytearray(open(full, "rb").read())
            if name == args.tamper_first and name not in tampered:
                tampered.add(name)
                data[len(data) // 2] ^= 0xFF
            return self.send(200, bytes(data), "application/octet-stream")
        self.send(404, b"{}")


ThreadingHTTPServer(("127.0.0.1", args.port), Handler).serve_forever()
