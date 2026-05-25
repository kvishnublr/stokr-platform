#!/usr/bin/env python3
"""Verify broker positions, fill sync, backfill, and quant validation on deployed API."""
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
TRADER_USER = os.environ.get("STOKR_TRADER_USER", "vishnualgo")
TRADER_PASS = os.environ.get("STOKR_TRADER_PASS", "Temp@12345678")


def req(method, path, token=None, body=None, timeout=120):
    url = BASE.rstrip("/") + path
    headers = {"Content-Type": "application/json", "Accept": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(r, timeout=timeout) as resp:
            return json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        print(f"HTTP {e.code} {path}: {e.read().decode(errors='replace')[:600]}", file=sys.stderr)
        raise


def login(principal, password):
    out = req("POST", "/api/auth/login", body={"principal": principal, "password": password})
    token = out.get("data", {}).get("accessToken") or out.get("data", {}).get("token")
    if not token:
        raise RuntimeError(f"login failed for {principal}: {out}")
    return token


def main():
    print("API", BASE)
    admin = login(ADMIN_USER, ADMIN_PASS)
    print("admin login ok")

    prov = req("GET", "/api/admin/signals/stats", token=admin)
    stats = prov.get("data") or {}
    print("production stats:", json.dumps(stats, indent=2))

    qv = req("GET", "/api/admin/signals/quant-validation", token=admin)
    print("quant-validation:", json.dumps(qv.get("data"), indent=2))

    try:
        trader = login(TRADER_USER, TRADER_PASS)
        print("trader login ok")
        status = req("GET", "/api/trader/broker/status", token=trader)
        st = status.get("data") or {}
        print("broker status:", json.dumps(st, indent=2))
        if st.get("connected") and st.get("testOrderEnabled"):
            if st.get("testOrderDryRun"):
                print("test-order: dry-run ON — set STOKR_ZERODHA_TEST_ORDER_DRY_RUN=false for live Kite order")
            else:
                body = {
                    "variety": "REGULAR",
                    "exchange": "NSE",
                    "tradingsymbol": "ITC",
                    "side": "BUY",
                    "quantity": 1,
                    "orderType": "MARKET",
                    "product": "MIS",
                }
                placed = req("POST", "/api/trader/broker/test-order", token=trader, body=body)
                print("test-order:", json.dumps(placed.get("data"), indent=2))
                print("waiting 25s for fill sync...")
                time.sleep(25)
        else:
            print("skip test-order: broker not connected or test orders disabled")
    except urllib.error.HTTPError:
        print("trader broker checks skipped (login or API error)")

    recon = req("POST", "/api/admin/reconciliation/trigger", token=admin)
    print("reconciliation trigger:", json.dumps(recon.get("data"), indent=2))

    now = datetime.now(timezone.utc)
    start = (now - timedelta(days=7)).isoformat().replace("+00:00", "Z")
    end = now.isoformat().replace("+00:00", "Z")
    job_body = {
        "brokerSource": "ZERODHA",
        "symbolGroup": "NIFTY_100",
        "timeframe": "1m",
        "rangeStart": start,
        "rangeEnd": end,
    }
    pre = req("POST", "/api/admin/market/backfill/preflight", token=admin, body=job_body)
    print("backfill preflight:", json.dumps(pre.get("data"), indent=2)[:1200])
    pf = pre.get("data") or {}
    blockers = pf.get("blockers") or []
    if blockers:
        print("backfill blocked:", blockers)
    else:
        job = req("POST", "/api/admin/market/backfill/jobs", token=admin, body=job_body)
        job_id = (job.get("data") or {}).get("jobId") or (job.get("data") or {}).get("id")
        print("backfill job:", job_id, json.dumps(job.get("data"), indent=2)[:800])
        if job_id:
            for _ in range(12):
                time.sleep(10)
                detail = req("GET", f"/api/admin/market/backfill/jobs/{job_id}", token=admin)
                d = detail.get("data") or {}
                print(" job status:", d.get("status"), "progress=", d.get("progressPercent"))
                if d.get("status") in ("COMPLETED", "FAILED", "CANCELLED"):
                    break

    health = req("GET", "/actuator/health")
    print("health:", health.get("status", health))
    print("done")


if __name__ == "__main__":
    main()
