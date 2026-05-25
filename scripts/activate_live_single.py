#!/usr/bin/env python3
"""Arm LIVE testing: any strategy may signal; risk allows only one open stock at a time."""
import json
import os
import sys
import urllib.error
import urllib.request

BASE = os.environ.get("STOKR_API_BASE", "http://127.0.0.1:8080")
ADMIN_USER = os.environ.get("STOKR_ADMIN_USER", "admin")
ADMIN_PASS = os.environ.get("STOKR_ADMIN_PASS", "password")
SYMBOL = os.environ.get("STOKR_LIVE_SYMBOL", "")
STRATEGY = os.environ.get("STOKR_LIVE_STRATEGY", "ALL")
TRADER = os.environ.get("STOKR_TRADER_USER", "vishnualgo")


def req(method, path, token=None, body=None):
    url = BASE.rstrip("/") + path
    headers = {"Content-Type": "application/json", "Accept": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    data = json.dumps(body).encode() if body else None
    r = urllib.request.Request(url, data=data, headers=headers, method=method)
    with urllib.request.urlopen(r, timeout=120) as resp:
        return json.loads(resp.read().decode())


def main():
    login = req("POST", "/api/auth/login", body={"principal": ADMIN_USER, "password": ADMIN_PASS})
    token = login.get("data", {}).get("accessToken") or login.get("data", {}).get("token")
    if not token:
        sys.exit("login failed")
    path = (
        f"/api/admin/signals/activate-live-single"
        f"?strategyKey={STRATEGY}&traderUsername={TRADER}"
        f"&armLive=true&runImmediatePoll=true"
    )
    if SYMBOL.strip():
        path += f"&symbol={SYMBOL.strip()}"
    out = req("POST", path, token=token)
    print(json.dumps(out.get("data"), indent=2))
    stats = req("GET", "/api/admin/signals/stats", token=token)
    print("stats:", json.dumps(stats.get("data"), indent=2))
    recent = req("GET", "/api/admin/signals?page=0&size=8&pipeline=LIVE", token=token)
    rows = recent.get("data", {}).get("content") or []
    print(f"recent LIVE signals:", len(rows))
    for row in rows:
        print(
            " -",
            row.get("createdAt"),
            row.get("strategyName"),
            row.get("signalType"),
            row.get("outcomeStatus"),
            row.get("confidenceScore"),
        )


if __name__ == "__main__":
    main()
