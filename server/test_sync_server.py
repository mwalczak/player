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


def call(method, path, key=None, body=None, headers=None):
    request = urllib.request.Request(BASE + path, method=method, data=None if body is None else json.dumps(body).encode())
    if key:
        request.add_header("Authorization", "Bearer " + key)
    for name, value in (headers or {}).items():
        request.add_header(name, value)
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

    def test_history_keeps_who_saved_what(self):
        key = "d" * 64
        tv = {"X-Device": "Sony BRAVIA 4K", "X-Real-Ip": "83.1.2.3"}
        phone = {"X-Device": "motorola edge 50", "X-Forwarded-For": "5.6.7.8, 10.42.0.1"}
        lib = lambda n: {"library": {"favorites": list(range(n)), "lists": [{"id": "a"}], "pinnedCategories": ["5"]}}
        call("PUT", "/v1/me", key, {"baseRevision": None, "data": lib(1)}, tv)
        call("PUT", "/v1/me", key, {"baseRevision": 1, "data": lib(2)}, tv)  # a moment later: same version
        call("PUT", "/v1/me", key, {"baseRevision": 2, "data": lib(3)}, phone)

        status, body = call("GET", "/v1/me/versions", key)
        versions = body["versions"]
        self.assertEqual([v["revision"] for v in versions], [3, 2])
        self.assertEqual((versions[0]["device"], versions[0]["ip"], versions[0]["favorites"]), ("motorola edge 50", "5.6.7.8", 3))
        self.assertEqual((versions[1]["device"], versions[1]["ip"], versions[1]["favorites"]), ("Sony BRAVIA 4K", "83.1.2.3", 2))
        self.assertEqual((versions[1]["lists"], versions[1]["pinned"]), (1, 1))
        self.assertNotIn("data", versions[0])

        status, old = call("GET", "/v1/me/versions/2", key)
        self.assertEqual((status, old["data"]), (200, lib(2)))
        self.assertEqual(call("GET", "/v1/me/versions/1", key)[0], 404)  # merged into 2
        self.assertEqual(call("GET", "/v1/me/versions/2", OTHER)[0], 404)  # only the owner sees it

    def test_history_keeps_the_last_100(self):
        key = "e" * 64
        for revision in range(105):
            device = {"X-Device": "device %d" % (revision % 2)}
            call("PUT", "/v1/me", key, {"baseRevision": revision or None, "data": {"n": revision}}, device)
        versions = call("GET", "/v1/me/versions", key)[1]["versions"]
        self.assertEqual(len(versions), 100)
        self.assertEqual((versions[0]["revision"], versions[-1]["revision"]), (105, 6))

    def test_rejects_bad_input(self):
        self.assertEqual(call("GET", "/v1/me", "short")[0], 401)
        self.assertEqual(call("GET", "/v1/me")[0], 401)
        self.assertEqual(call("PUT", "/v1/me", "c" * 64, {"data": "nope"})[0], 400)
        self.assertEqual(call("GET", "/v1/profiles/0OIL1234")[0], 404)
        self.assertEqual(call("GET", "/healthz")[0], 200)


if __name__ == "__main__":
    unittest.main()
