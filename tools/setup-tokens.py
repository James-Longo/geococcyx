#!/usr/bin/env python3
"""setup-tokens.py — one-time Google sign-in (Drive and Health) for the watch.

Writes ../config.json: tools/config.example.json settings (or the existing
config.json) plus the credentials the watch needs to upload runs.

  python3 tools/setup-tokens.py google   # needs ../google-client.json, a Google OAuth desktop client (gitignored)
"""
import http.server
import json
import os
import sys
import urllib.parse
import urllib.request
import webbrowser

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
CONFIG = os.path.join(ROOT, "config.json")
GOOGLE_CLIENT = os.path.join(ROOT, "google-client.json")
# Drive: only files this app creates (its run folder).
GOOGLE_SCOPES = ["https://www.googleapis.com/auth/drive.file"]


def load_config():
    path = CONFIG if os.path.exists(CONFIG) else os.path.join(HERE, "config.example.json")
    return json.load(open(path))


def save_config(cfg):
    fd = os.open(CONFIG, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        json.dump(cfg, f, indent=2)
    print("wrote", CONFIG)


def wait_for_code(url_for):
    """Serve one loopback redirect and return its query parameters."""
    got = {}

    class H(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            got.update(urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query))
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b"Done - you can close this tab.")

        def log_message(self, *a):
            pass

    srv = http.server.HTTPServer(("127.0.0.1", 0), H)
    url = url_for(f"http://127.0.0.1:{srv.server_port}")
    print("Opening browser:\n" + url, flush=True)
    webbrowser.open(url)
    while "code" not in got and "error" not in got:
        srv.handle_request()
    if "error" in got:
        sys.exit("Denied: " + got["error"][0])
    return got["code"][0], f"http://127.0.0.1:{srv.server_port}"


def google():
    # The client file as Google Cloud downloads it: {"installed": {...}} (or "web").
    client = next(iter(json.load(open(GOOGLE_CLIENT)).values()))
    code, redirect = wait_for_code(lambda redirect: "https://accounts.google.com/o/oauth2/v2/auth?" + urllib.parse.urlencode({
        "client_id": client["client_id"], "redirect_uri": redirect, "response_type": "code",
        "scope": " ".join(GOOGLE_SCOPES), "access_type": "offline", "prompt": "consent"}))
    tok = json.load(urllib.request.urlopen(urllib.request.Request(
        "https://oauth2.googleapis.com/token", urllib.parse.urlencode({
            "client_id": client["client_id"], "client_secret": client["client_secret"],
            "code": code, "redirect_uri": redirect, "grant_type": "authorization_code"}).encode())))
    cfg = load_config()
    cfg["google"] = {"clientId": client["client_id"], "clientSecret": client["client_secret"],
                     "refreshToken": tok["refresh_token"]}
    save_config(cfg)

if __name__ == "__main__":
    {"google": google}.get(sys.argv[1] if len(sys.argv) > 1 else "", lambda: sys.exit(__doc__))()
