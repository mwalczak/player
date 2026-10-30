#!/usr/bin/env python3
"""Runs the sync server on a temporary database and goes through what the app does."""
import json
import os
import sys
import tempfile
import threading
import unittest
import urllib.error
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
os.environ["SYNC_DB"] = os.path.join(tempfile.mkdtemp(), "sync.db")
import sync_server  # noqa: E402

OWNER = "a" * 64
OTHER = "b" * 64


def call(method, path, key=None, body=None):
    request = urllib.request.Request(BASE + path, method=method, data=None if body is None else json.dumps(body).encode())
    if key:
        request.add_header("Authorization", "Bearer " + key)
    try:
        with urllib.request.urlopen(request) as response:
            text = response.read()
            return response.status, json.loads(text) if text else None
    except urllib.error.HTTPError as e:
        text = e.read()
        return e.code, json.loads(text) if text else None


class SyncServerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        global BASE
        sync_server.init_db()
        server = sync_server.ThreadingHTTPServer(("127.0.0.1", 0), sync_server.Handler)
        BASE = "http://127.0.0.1:%d" % server.server_address[1]
        threading.Thread(target=server.serve_forever, daemon=True).start()

    def test_owner_flow_and_sharing(self):
        self.assertEqual(call("GET", "/v1/me", OWNER)[0], 404)
        status, created = call("PUT", "/v1/me", OWNER, {"baseRevision": None, "data": {"favorites": [1]}})
        self.assertEqual((status, created["revision"]), (200, 1))
        code = created["code"]
        self.assertEqual(len(code), 8)

        status, mine = call("GET", "/v1/me", OWNER)
        self.assertEqual((mine["code"], mine["data"]), (code, {"favorites": [1]}))
        self.assertEqual(call("GET", "/v1/me?since=1", OWNER)[0], 304)

        self.assertEqual(call("PUT", "/v1/me", OWNER, {"baseRevision": 1, "data": {"favorites": [1, 2]}})[1]["revision"], 2)
        status, current = call("PUT", "/v1/me", OWNER, {"baseRevision": 1, "data": {"favorites": [3]}})
        self.assertEqual((status, current["revision"], current["data"]), (409, 2, {"favorites": [1, 2]}))

        # Friends read by code, typed any way.
        pretty = "%s-%s" % (code[:4].lower(), code[4:])
        status, shared = call("GET", "/v1/profiles/" + pretty)
        self.assertEqual((status, shared["data"]), (200, {"favorites": [1, 2]}))
        self.assertEqual(call("GET", "/v1/profiles/%s?since=2" % code)[0], 304)
        # …but can't write: their own key is their own profile.
        call("PUT", "/v1/me", OTHER, {"baseRevision": None, "data": {"favorites": [9]}})
        self.assertEqual(call("GET", "/v1/profiles/" + code)[1]["data"], {"favorites": [1, 2]})

    def test_rejects_bad_input(self):
        self.assertEqual(call("GET", "/v1/me", "short")[0], 401)
        self.assertEqual(call("GET", "/v1/me")[0], 401)
        self.assertEqual(call("PUT", "/v1/me", "c" * 64, {"data": "nope"})[0], 400)
        self.assertEqual(call("GET", "/v1/profiles/0OIL1234")[0], 404)
        self.assertEqual(call("GET", "/healthz")[0], 200)


if __name__ == "__main__":
    unittest.main()
