#!/usr/bin/env python3
"""HTTP/database acceptance checks against a RUNNING local DEMO_MODE instance.

Uses only Python's standard library and the bundled demonstration accounts.
Creates uniquely named test reports; removes open test reports in finally.
Approved/resolved records remain as auditable test history. Never run against
an instance with real users or data. This script is not a mock database test.

  python3 scripts/integration_test.py --base-url http://127.0.0.1:8080
"""
from __future__ import annotations

import argparse
import http.cookiejar
import json
import struct
import sys
import traceback
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zlib
from datetime import datetime
from zoneinfo import ZoneInfo


class Client:
    def __init__(self, base: str):
        self.base = base.rstrip("/")
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        self.csrf = ""
        self.session()

    def request(self, method, path, data=None, *, headers=None, raw=False, csrf=True):
        h = {"Accept": "application/json"}
        if csrf and method != "GET":
            h["X-CSRF-Token"] = self.csrf
        if data is not None and not isinstance(data, bytes):
            data = json.dumps(data).encode("utf-8")
            h["Content-Type"] = "application/json"
        h.update(headers or {})
        req = urllib.request.Request(self.base + path, data=data, headers=h, method=method)
        try:
            response = self.opener.open(req, timeout=30)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            content = response.read()
            code = response.status
            result = content if raw else json.loads(content.decode("utf-8"))
            return code, result, {key.lower(): value for key, value in response.headers.items()}

    def session(self):
        code, body, _ = self.request("GET", "/api/session")
        if code != 200:
            raise RuntimeError(f"Cannot establish session: HTTP {code}: {body}")
        self.csrf = body["csrfToken"]
        return body

    def login(self, username, password):
        code, body, _ = self.request("POST", "/api/login", {"username": username, "password": password})
        if code != 200:
            raise RuntimeError(f"Cannot sign in as {username}: HTTP {code}: {body}")
        self.session()  # Sign-in rotates both the cookie and CSRF token.


def png_fixture():
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xffffffff)
    pixels = b"\0" + b"\x55\x88\xaa" * 2
    return (b"\x89PNG\r\n\x1a\n" +
            chunk(b"IHDR", struct.pack(">IIBBBBB", 2, 2, 8, 2, 0, 0, 0)) +
            chunk(b"tEXt", b"Comment\0REUNITE_TEST_METADATA_MUST_BE_REMOVED") +
            chunk(b"IDAT", zlib.compress(pixels * 2)) + chunk(b"IEND", b""))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--keep-data", action="store_true", help="Keep all created test reports, including open ones")
    args = parser.parse_args()
    parsed = urllib.parse.urlparse(args.base_url)
    if parsed.scheme not in ("http", "https") or parsed.hostname not in ("localhost", "127.0.0.1", "::1"):
        parser.error("Use a local demonstration instance on localhost, 127.0.0.1 or ::1.")
    count = 0
    created = []

    def check(condition, message):
        nonlocal count
        if not condition:
            raise AssertionError(message)
        count += 1
        print(f"PASS {count:02d} {message}")

    def expect(client, method, path, status=200, data=None, **kwargs):
        code, body, headers = client.request(method, path, data, **kwargs)
        if code != status:
            raise AssertionError(f"{method} {path}: expected HTTP {status}, got {code}: {body!r}")
        return body, headers

    def get_report(client, rid):
        return expect(client, "GET", f"/api/reports/{rid}")[0]["report"]

    def make(client, data):
        body, _ = expect(client, "POST", "/api/reports", data=data)
        report = body["report"]
        created.append((client, report["id"]))
        return report

    def edit(client, rid, payload):
        current = get_report(client, rid)
        data = dict(payload, version=current["version"])
        return expect(client, "PUT", f"/api/reports/{rid}", data=data)[0]["report"]

    guest = Client(args.base_url)
    check(guest.session().get("demoMode") is True, "instance explicitly reports local demo mode")
    alice, bob, admin = Client(args.base_url), Client(args.base_url), Client(args.base_url)
    alice.login("alice", "AliceDemo1!")
    bob.login("bob", "BobDemo1!")
    admin.login("admin", "AdminDemo1!")
    tag = "reunitetest" + uuid.uuid4().hex[:12]
    day = datetime.now(ZoneInfo("America/Los_Angeles")).date().isoformat()
    public_keys = {"id", "type", "visibility", "title", "description", "category", "subcategory", "color", "brand", "eventDate", "location", "status", "version", "createdAt", "updatedAt", "hasPhoto"}
    found_body = {
        "type": "FOUND", "visibility": "PRIVATE", "title": "Copper wireless headphones",
        "description": f"Copper wireless headphones with embossed constellation and violet corner sticker {tag}.",
        "category": "ELECTRONICS", "subcategory": "HEADPHONES", "color": "Copper", "brand": "Orbit",
        "eventDate": day, "location": "Leavey Library", "privateDetails": "Private evidence marker " + tag,
        "verificationQuestion": "Describe the distinctive exterior constellation marking.",
        "verificationAnswer": "  Violet   Moon  ",
    }
    lost_body = dict(found_body, type="LOST", visibility="PUBLIC", verificationQuestion="", verificationAnswer="", privateDetails="Private owner evidence " + tag)
    found_id = None
    try:
        expect(guest, "POST", "/api/reports", 401, found_body)
        check(True, "guest cannot create a report even with a valid anonymous CSRF token")
        expect(bob, "POST", "/api/reports", 403, found_body, csrf=False)
        check(True, "authenticated write without CSRF token is rejected")
        found = make(bob, found_body)
        found_id = found["id"]
        check(found["visibility"] == "PRIVATE", "private found visibility is persisted")
        for client, who in ((guest, "guest"), (alice, "unmatched signed-in user")):
            expect(client, "GET", f"/api/reports/{found_id}", 404)
            expect(client, "GET", f"/api/reports/{found_id}/photo", 404)
            check(True, f"{who} cannot enumerate private details or photo")
        png = png_fixture()
        current = get_report(bob, found_id)
        expect(bob, "POST", f"/api/reports/{found_id}/photo", data=png,
               headers={"Content-Type": "image/png", "X-Report-Version": str(current["version"])})
        stored, headers = expect(bob, "GET", f"/api/reports/{found_id}/photo", raw=True)
        check(stored.startswith(b"\x89PNG\r\n\x1a\n") and b"REUNITE_TEST_METADATA" not in stored,
              "photo is decoded and stored as sanitized PNG without supplied metadata")
        check(headers.get("cache-control") == "no-store", "photo response disables browser caching")
        expect(alice, "GET", f"/api/reports/{found_id}/photo", 404)
        check(True, "uploaded photo remains inaccessible before a lost-report match")
        current = get_report(bob, found_id)
        expect(bob, "POST", f"/api/reports/{found_id}/photo", 400, png,
               headers={"Content-Type": "image/jpeg", "X-Report-Version": str(current["version"])})
        check(True, "image bytes masquerading as another MIME type are rejected")
        query = "/api/search?" + urllib.parse.urlencode({"q": tag})
        result, _ = expect(guest, "GET", query)
        check(result["privateCount"] is None and result["reports"] == [], "guest search reveals no private count or cards")
        result, _ = expect(alice, "GET", query)
        check(result["privateCount"] == 1 and result["reports"] == [], "authenticated general search returns only the private count")
        check(tag not in json.dumps({k:v for k,v in result.items() if k != "privateCountMessage"}), "count response does not contain private report descriptors")
        expect(alice, "PUT", f"/api/reports/{found_id}", 403, dict(found_body, version=current["version"]))
        expect(alice, "DELETE", f"/api/reports/{found_id}?version={current['version']}", 403)
        check(True, "another user cannot edit or remove the found report")
        lost = make(alice, lost_body)
        lost_id = lost["id"]
        check(lost["visibility"] == "PRIVATE", "server forces lost reports private despite a forged PUBLIC value")
        revealed, _ = expect(alice, "GET", f"/api/reports/{found_id}")
        check(revealed["canClaim"] and revealed["verificationQuestion"] == found_body["verificationQuestion"], "own active lost match reveals found details and verification question")
        check(set(revealed["report"]).issubset(public_keys), "match reveal excludes owner contact, private evidence and stored hashes")
        expect(alice, "GET", f"/api/reports/{found_id}/photo", raw=True)
        check(True, "same match gate authorizes the private photo")
        me, _ = expect(alice, "GET", "/api/me")
        check(any(m["foundReport"]["id"] == found_id and m["lostReportId"] == lost_id for m in me["matches"]), "dashboard contains the matching lost–found pair")
        expect(alice, "PUT", f"/api/reports/{lost_id}", 409, dict(lost_body, version=0))
        check(True, "stale report version is rejected")
        contradictory = dict(lost_body, category="CLOTHING", subcategory="JACKET")
        edit(alice, lost_id, contradictory)
        expect(alice, "GET", f"/api/reports/{found_id}", 404)
        expect(alice, "GET", f"/api/reports/{found_id}/photo", 404)
        check(True, "editing away matching evidence immediately revokes private detail and photo access")
        edit(alice, lost_id, lost_body)
        expect(alice, "GET", f"/api/reports/{found_id}")
        check(True, "correcting the lost report restores eligible access")
        public_body = dict(found_body, visibility="PUBLIC", verificationQuestion="", verificationAnswer="")
        edit(bob, found_id, public_body)
        public, _ = expect(guest, "GET", f"/api/reports/{found_id}")
        check(set(public["report"]).issubset(public_keys) and public["verificationQuestion"] is None,
              "public detail excludes private evidence and the guest verification question")
        expect(guest, "GET", f"/api/reports/{found_id}/photo", raw=True)
        check(True, "public report photo is available to guests")
        edit(bob, found_id, dict(public_body, visibility="PRIVATE"))
        expect(guest, "GET", f"/api/reports/{found_id}", 404)
        expect(guest, "GET", f"/api/reports/{found_id}/photo", 404)
        check(True, "switching back to private revokes guest detail and photo access")
        expect(alice, "GET", "/api/admin", 403)
        check(True, "ordinary user cannot access administration")
        claim_body = {"foundReportId": found_id, "lostReportId": lost_id, "answer": "violet moon", "explanation": "Integration test: exterior marking matches owner evidence."}
        claim, _ = expect(alice, "POST", "/api/claims", data=claim_body)
        claim_id = claim["id"]
        expect(alice, "POST", "/api/claims", 409, claim_body)
        check(True, "duplicate pending claim is prevented")
        review, _ = expect(admin, "GET", "/api/admin")
        review_claim = next(c for c in review["claims"] if c["id"] == claim_id)
        check(review_claim.get("answerMatches") is True, "administrator sees normalized verification result")
        check(not any(k in review_claim for k in ("answer", "answerHash", "verificationAnswer")), "administrator claim list does not reveal answer text or hashes")
        decision = {"decision": "APPROVED", "reason": "Integration test: independently reviewed exterior ownership evidence."}
        expect(alice, "POST", f"/api/admin/claims/{claim_id}/decision", 403, decision)
        check(True, "ordinary user cannot approve ownership")
        expect(admin, "POST", f"/api/admin/claims/{claim_id}/decision", data=decision)
        check(get_report(bob, found_id)["status"] == "RESOLVED" and get_report(alice, lost_id)["status"] == "RESOLVED", "approval atomically resolves the found and linked lost reports")
        expect(admin, "POST", f"/api/admin/claims/{claim_id}/decision", 409, decision)
        check(True, "processed claim cannot be approved twice")
        expect(alice, "GET", f"/api/reports/{found_id}", 404)
        expect(alice, "GET", f"/api/reports/{found_id}/photo", 404)
        check(True, "resolved private report and photo no longer use historical matches for disclosure")
        expect(alice, "POST", "/api/logout", data={})
        alice.session()
        expect(alice, "GET", "/api/me", 401)
        check(True, "sign-out invalidates authenticated dashboard access")
        print(f"\nSUCCESS: {count} HTTP/database acceptance assertions passed.")
        print(f"Test marker: {tag}. Resolved reports remain in demonstration history.")
        return 0
    finally:
        if not args.keep_data:
            for client, rid in reversed(created):
                try:
                    code, details, _ = client.request("GET", f"/api/reports/{rid}")
                    if code == 200 and details["report"]["status"] == "OPEN":
                        client.request("DELETE", f"/api/reports/{rid}?version={details['report']['version']}")
                except Exception as error:
                    print(f"Cleanup could not remove open test report {rid}: {error}", file=sys.stderr)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (AssertionError, RuntimeError, urllib.error.URLError, TimeoutError):
        traceback.print_exc()
        sys.exit(1)
