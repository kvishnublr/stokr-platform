#!/usr/bin/env python3
"""Smoke-check broker position truth API (trader JWT required via env or login)."""
import json
import os
import sys
import urllib.request

BASE = os.environ.get("STOKR_API", "http://127.0.0.1:8080").rstrip("/")
TOKEN = os.environ.get("STOKR_TOKEN", "")


def get(path: str):
    req = urllib.request.Request(
        f"{BASE}{path}",
        headers={"Authorization": f"Bearer {TOKEN}", "Accept": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read().decode())


def main():
    if not TOKEN:
        print("Set STOKR_TOKEN to a trader JWT", file=sys.stderr)
        sys.exit(1)
    truth = get("/api/trader/terminal/broker-truth")
    data = truth.get("data") or truth
    print("syncState:", data.get("syncState"))
    print("positions:", len(data.get("positions") or []))
    print("mismatches:", len(data.get("mismatches") or []))
    ws = get("/api/trader/terminal/workstation")
    wdata = ws.get("data") or ws
    bt = wdata.get("brokerTruth") or {}
    print("workstation.brokerTruth.syncState:", bt.get("syncState"))
    open_pos = wdata.get("openPositions") or []
    print("openPositions:", len(open_pos))
    if open_pos:
        print("sample:", json.dumps(open_pos[0], default=str)[:400])


if __name__ == "__main__":
    main()
