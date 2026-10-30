#!/usr/bin/env python3
"""
Bunny Player sync service: keeps each person's favorites, lists, pinned categories and settings,
and shares them with friends by a short code.

- A person's own devices find their profile with an owner key the app derives from the mybunny
  login (PBKDF2 on the device). The server never sees the login, and stores only a hash of the key.
- Anyone with a profile's share code can read it (follow it or copy it), never change it.

Standard library only, so it runs on a plain python image. Data lives in one SQLite file.

  GET  /healthz
  GET  /v1/me[?since=N]               Authorization: Bearer <owner key>  -> profile | 304 | 404
  PUT  /v1/me  {"baseRevision", "data"}  creates or updates; 409 with the current profile if
                                         baseRevision is stale
  GET  /v1/profiles/<code>[?since=N]  -> profile | 304 | 404
"""
import hashlib
import json
import os
import re
import secrets
import sqlite3
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

DB_PATH = os.environ.get("SYNC_DB", "/data/sync.db")
PORT = int(os.environ.get("PORT", "8080"))
MAX_BODY = 1024 * 1024
# No 0/O or 1/I, so a code read off a TV screen can't be mistyped.
ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
CODE_LEN = 8
KEY_RE = re.compile(r"^[0-9a-f]{64}$")
# Wrong share codes allowed per address and hour, so codes can't be guessed.
MISSES_PER_HOUR = 60

_write_lock = threading.Lock()
_misses = {}
_misses_lock = threading.Lock()


def connect():
    db = sqlite3.connect(DB_PATH, timeout=10)
    db.row_factory = sqlite3.Row
    return db


def init_db():
    os.makedirs(os.path.dirname(DB_PATH) or ".", exist_ok=True)
    with connect() as db:
        db.execute("PRAGMA journal_mode=WAL")
        db.execute(
            """CREATE TABLE IF NOT EXISTS profiles (
                owner TEXT PRIMARY KEY,
                code TEXT UNIQUE NOT NULL,
                revision INTEGER NOT NULL,
                data TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )"""
        )


def new_code(db):
    while True:
        code = "".join(secrets.choice(ALPHABET) for _ in range(CODE_LEN))
        if db.execute("SELECT 1 FROM profiles WHERE code = ?", (code,)).fetchone() is None:
            return code


def normalize_code(text):
    """Accepts "abcd-efgh", "ABCD EFGH", …; returns None for anything that can't be a code."""
    code = re.sub(r"[\s-]", "", text).upper()
    if len(code) != CODE_LEN or any(c not in ALPHABET for c in code):
        return None
    return code


def profile_json(row):
    return {
        "code": row["code"],
        "revision": row["revision"],
        "updatedAt": row["updated_at"],
        "data": json.loads(row["data"]),
    }


def too_many_misses(address):
    now = time.time()
    with _misses_lock:
        recent = [t for t in _misses.get(address, []) if now - t < 3600]
        _misses[address] = recent
        return len(recent) >= MISSES_PER_HOUR


def note_miss(address):
    with _misses_lock:
        _misses.setdefault(address, []).append(time.time())


class Handler(BaseHTTPRequestHandler):
    server_version = "BunnySync/1"

    def log_message(self, fmt, *args):
        # One line per request, without addresses or keys.
        print("%s %s" % (self.command, fmt % args), flush=True)

    # --- helpers ---------------------------------------------------------------------------

    def send_json(self, status, body=None):
        payload = b"" if body is None else json.dumps(body, separators=(",", ":")).encode()
        self.send_response(status)
        if body is not None:
            self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        if payload:
            self.wfile.write(payload)

    def error(self, status, message):
        self.send_json(status, {"error": message})

    def owner(self):
        """Hash of the owner key, or None (after answering 401) when it's missing or malformed."""
        auth = self.headers.get("Authorization", "")
        key = auth[7:].strip().lower() if auth.startswith("Bearer ") else ""
        if not KEY_RE.match(key):
            self.error(401, "missing or invalid key")
            return None
        return hashlib.sha256(key.encode()).hexdigest()

    def address(self):
        forwarded = self.headers.get("X-Forwarded-For", "")
        return forwarded.split(",")[0].strip() or self.headers.get("X-Real-IP") or self.client_address[0]

    def since(self, query):
        try:
            return int(parse_qs(query).get("since", [""])[0])
        except ValueError:
            return None

    def read_body(self):
        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0 or length > MAX_BODY:
            self.error(413 if length > MAX_BODY else 400, "body missing or too large")
            return None
        try:
            body = json.loads(self.rfile.read(length))
        except ValueError:
            self.error(400, "invalid json")
            return None
        if not isinstance(body, dict) or not isinstance(body.get("data"), dict):
            self.error(400, "expected {\"baseRevision\": n, \"data\": {...}}")
            return None
        return body

    # --- routes ----------------------------------------------------------------------------

    def do_GET(self):
        url = urlparse(self.path)
        if url.path == "/healthz":
            return self.send_json(200, {"ok": True})
        if url.path == "/v1/me":
            owner = self.owner()
            if owner is None:
                return
            with connect() as db:
                row = db.execute("SELECT * FROM profiles WHERE owner = ?", (owner,)).fetchone()
            return self.answer(row, self.since(url.query))
        match = re.fullmatch(r"/v1/profiles/([^/]+)", url.path)
        if match:
            address = self.address()
            if too_many_misses(address):
                return self.error(429, "too many wrong codes, try again later")
            code = normalize_code(match.group(1))
            row = None
            if code:
                with connect() as db:
                    row = db.execute("SELECT * FROM profiles WHERE code = ?", (code,)).fetchone()
            if row is None:
                note_miss(address)
            return self.answer(row, self.since(url.query))
        self.error(404, "not found")

    def answer(self, row, since):
        if row is None:
            return self.error(404, "no such profile")
        if since is not None and since >= row["revision"]:
            return self.send_json(304)
        self.send_json(200, profile_json(row))

    def do_PUT(self):
        if urlparse(self.path).path != "/v1/me":
            return self.error(404, "not found")
        owner = self.owner()
        if owner is None:
            return
        body = self.read_body()
        if body is None:
            return
        base = body.get("baseRevision")
        data = json.dumps(body["data"], separators=(",", ":"))
        now = int(time.time() * 1000)
        with _write_lock, connect() as db:
            row = db.execute("SELECT * FROM profiles WHERE owner = ?", (owner,)).fetchone()
            if row is None:
                code = new_code(db)
                db.execute(
                    "INSERT INTO profiles (owner, code, revision, data, updated_at) VALUES (?, ?, 1, ?, ?)",
                    (owner, code, data, now),
                )
                return self.send_json(200, {"code": code, "revision": 1, "updatedAt": now})
            if base != row["revision"]:
                return self.send_json(409, profile_json(row))
            revision = row["revision"] + 1
            db.execute(
                "UPDATE profiles SET revision = ?, data = ?, updated_at = ? WHERE owner = ?",
                (revision, data, now, owner),
            )
            self.send_json(200, {"code": row["code"], "revision": revision, "updatedAt": now})


def main():
    init_db()
    server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    print("Bunny sync listening on :%d, data in %s" % (PORT, DB_PATH), flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
