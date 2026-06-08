#!/usr/bin/env python3
"""Trigger admin backfill for missing signal outcome exit OMS legs."""
import json
import os
import sys
import urllib.error
import urllib.request

BASE = os.environ.get("STOKR_API_BASE", "http://127.0.0.1:8080")
ADMIN_USER = os.environ.get("STOKR_ADMIN_USER", "admin@stokr.local")
ADMIN_PASS = os.environ.get("STOKR_ADMIN_PASS", "admin123")
LOOKBACK_HOURS = int(os.environ.get("STOKR_EXIT_BACKFILL_HOURS", "72"))
MAX_SIGNALS = int(os.environ.get("STOKR_EXIT_BACKFILL_MAX", "200"))


def req(method, path, token=None, body=None, timeout=300):
    url = BASE.rstrip("/") + path
    headers = {"Content-Type": "application/json", "Accept": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(url, data=data, headers=headers, method=method)
    with urllib.request.urlopen(r, timeout=timeout) as resp:
        return json.loads(resp.read().decode())


def main() -> int:
    print(f"API={BASE} lookbackHours={LOOKBACK_HOURS} maxSignals={MAX_SIGNALS}", flush=True)
    login = req("POST", "/api/auth/login", body={"principal": ADMIN_USER, "password": ADMIN_PASS})
    token = (login.get("data") or {}).get("accessToken") or (login.get("data") or {}).get("token")
    if not token:
        print("login failed:", login, file=sys.stderr)
        return 1
    path = f"/api/admin/signals/backfill-outcome-exits?lookbackHours={LOOKBACK_HOURS}&maxSignals={MAX_SIGNALS}"
    try:
        out = req("POST", path, token=token)
    except urllib.error.HTTPError as e:
        print(f"backfill HTTP {e.code}: {e.read().decode(errors='replace')[:800]}", file=sys.stderr)
        return 1
    print(json.dumps(out, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
