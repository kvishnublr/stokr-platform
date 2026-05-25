#!/usr/bin/env python3
"""Activate signal pipeline and verify admin signals API (run on server or against public URL)."""
import json
import os
import sys
import urllib.error
import urllib.request

BASE = os.environ.get("STOKR_API_BASE", "http://127.0.0.1:8080")
ADMIN_USER = os.environ.get("STOKR_ADMIN_USER", "admin")
ADMIN_PASS = os.environ.get("STOKR_ADMIN_PASS", "password")


def req(method, path, token=None, body=None):
    url = BASE.rstrip("/") + path
    data = None
    headers = {"Content-Type": "application/json", "Accept": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if body is not None:
        data = json.dumps(body).encode("utf-8")
    r = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(r, timeout=120) as resp:
            return json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        err_body = e.read().decode(errors="replace")
        print(f"HTTP {e.code} {path}: {err_body[:800]}", file=sys.stderr)
        raise


def main():
    login = req("POST", "/api/auth/login", body={"principal": ADMIN_USER, "password": ADMIN_PASS})
    token = login.get("data", {}).get("accessToken") or login.get("data", {}).get("token")
    if not token:
        print("login failed:", login, file=sys.stderr)
        sys.exit(1)
    print("login ok")

    act = req(
        "POST",
        "/api/admin/signals/activate-pipeline?syncUniverses=true&runImmediatePoll=true",
        token=token,
    )
    print("activate-pipeline:", json.dumps(act.get("data"), indent=2))

    stats = req("GET", "/api/admin/signals/stats", token=token)
    print("stats:", json.dumps(stats.get("data"), indent=2))

    sigs = req("GET", "/api/admin/signals?page=0&size=10&includeTestTrades=false", token=token)
    content = sigs.get("data", {}).get("content") or []
    print(f"signals (non-test): {len(content)} rows on first page, total={sigs.get('data', {}).get('totalElements')}")
    for row in content[:5]:
        print(
            " -",
            row.get("createdAt"),
            row.get("strategyName"),
            row.get("symbol"),
            row.get("signalType"),
            row.get("pipeline"),
        )


if __name__ == "__main__":
    main()
