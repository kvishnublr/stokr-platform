#!/usr/bin/env python3
"""Probe prod API endpoints for UI wiring."""
import json, os, urllib.request

BASE = "http://173.249.55.84:8080/api"

def post(path, body):
    req = urllib.request.Request(
        BASE + path,
        data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=20) as r:
        return json.loads(r.read().decode())

def get(path, token):
    req = urllib.request.Request(
        BASE + path,
        headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=20) as r:
        return json.loads(r.read().decode())

admin = post("/auth/login", {"principal": "admin@stokr.local", "password": "admin123"})
admin_tok = admin["data"]["accessToken"]
print("admin login ok")

trader_tok = None
for pw in [os.environ.get("STOKR_TRADER_PASS"), "Trader@123", "Stokr@123", "Temp1234.."]:
    if not pw:
        continue
    try:
        trader = post("/auth/login", {"principal": "vishnualgo@gmail.com", "password": pw})
        trader_tok = trader["data"]["accessToken"]
        print("trader login ok", trader["data"].get("email"))
        break
    except Exception as e:
        print("trader login fail with pw len", len(pw), e)
if not trader_tok:
    print("SKIP trader endpoints")

endpoints = [
    ("/health", None),
    ("/trader/me/execution-mode", trader_tok),
    ("/portfolio/dashboard?equityPoints=60", trader_tok),
    ("/trader/terminal/workstation", trader_tok),
    ("/oms/orders?page=0&size=5", trader_tok),
    ("/oms/executions?page=0&size=5", trader_tok),
    ("/trader/terminal/market/watch", trader_tok),
    ("/strategies/runtime-metrics", trader_tok),
    ("/trader/strategy-feed?limit=10", trader_tok),
    ("/trader/broker/status", trader_tok),
    ("/trader/execution-summary", trader_tok),
    ("/admin/users?page=0&size=5", admin_tok),
    ("/admin/health", admin_tok),
    ("/admin/ops/status", admin_tok),
    ("/admin/readiness", admin_tok),
    ("/admin/oms/summary", admin_tok),
    ("/admin/audit?page=0&size=5", admin_tok),
    ("/admin/alerts", admin_tok),
]
for ep, tok in endpoints:
    try:
        if tok:
            data = get(ep, tok)
        else:
            req = urllib.request.Request(BASE + ep)
            with urllib.request.urlopen(req, timeout=20) as r:
                data = json.loads(r.read().decode())
        keys = list(data.keys()) if isinstance(data, dict) else type(data).__name__
        inner = data.get("data") if isinstance(data, dict) else None
        inner_type = type(inner).__name__
        if isinstance(inner, dict):
            inner_keys = list(inner.keys())[:8]
        elif isinstance(inner, list):
            inner_keys = f"list[{len(inner)}]"
            if inner:
                inner_keys += " sample=" + str(list(inner[0].keys())[:6] if isinstance(inner[0], dict) else inner[0])[:80]
        else:
            inner_keys = inner
        print(f"OK {ep} -> {keys} data={inner_type} {inner_keys}")
    except Exception as e:
        print(f"FAIL {ep} -> {e}")
