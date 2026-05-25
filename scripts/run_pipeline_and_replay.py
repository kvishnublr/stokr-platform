#!/usr/bin/env python3
"""Activate pipeline, seed replay candles if needed, run replays, print signal counts."""
import json
import os
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timedelta, timezone

BASE = os.environ.get("STOKR_API_BASE", "http://127.0.0.1:8080")
ADMIN_USER = os.environ.get("STOKR_ADMIN_USER", "admin")
ADMIN_PASS = os.environ.get("STOKR_ADMIN_PASS", "password")


def req(method, path, token=None, body=None, timeout=120):
    url = BASE.rstrip("/") + path
    headers = {"Content-Type": "application/json", "Accept": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(url, data=data, headers=headers, method=method)
    with urllib.request.urlopen(r, timeout=timeout) as resp:
        return json.loads(resp.read().decode())


def main():
    login = req("POST", "/api/auth/login", body={"principal": ADMIN_USER, "password": ADMIN_PASS})
    token = login.get("data", {}).get("accessToken") or login.get("data", {}).get("token")
    if not token:
        print("login failed", login, file=sys.stderr)
        sys.exit(1)

    act = req(
        "POST",
        "/api/admin/signals/activate-pipeline?syncUniverses=true&runImmediatePoll=true",
        token=token,
    )
    print("activate:", json.dumps(act.get("data"), indent=2))

    try:
        seed = req("POST", "/api/admin/signals/seed-replay-candles", token=token, timeout=300)
        print("seed:", json.dumps(seed.get("data"), indent=2))
    except urllib.error.HTTPError as e:
        if e.code != 404:
            raise
        print("seed-replay-candles not deployed yet (404)")

    end = datetime.now(timezone.utc).date()
    start = end - timedelta(days=5)
    replays = [
        ("NSE_SPIKE_DETECTION", start.isoformat(), end.isoformat()),
        ("OPENING_RANGE_BREAKOUT", start.isoformat(), end.isoformat()),
        ("BREAKOUT_COMMODITIES", start.isoformat(), end.isoformat()),
    ]
    for key, fr, to in replays:
        try:
            rep = req(
                "POST",
                f"/api/admin/signals/replay?strategyKey={key}&from={fr}&to={to}",
                token=token,
            )
            print(f"replay {key}:", rep.get("data"))
        except Exception as ex:
            print(f"replay {key} failed:", ex)

    time.sleep(90)
    stats = req("GET", "/api/admin/signals/stats", token=token)
    print("stats:", json.dumps(stats.get("data"), indent=2))
    sigs = req("GET", "/api/admin/signals?page=0&size=5&includeTestTrades=false", token=token)
    d = sigs.get("data") or {}
    print(f"non-test signals: total={d.get('totalElements')} sample={len(d.get('content') or [])}")
    for row in (d.get("content") or [])[:5]:
        print(" -", row.get("createdAt"), row.get("strategyName"), row.get("symbol"), row.get("signalType"))


if __name__ == "__main__":
    main()
