#!/usr/bin/env python3
"""Check the latest signal in detail."""
import urllib.request, json
BASE = "http://localhost:8080"
token = json.load(urllib.request.urlopen(urllib.request.Request(
    BASE+"/api/auth/login",
    data=json.dumps({"principal":"admin","password":"admin123"}).encode(),
    headers={"Content-Type":"application/json"})))["data"]["accessToken"]
H = {"Authorization": "Bearer "+token}

# Get latest signal
r = json.load(urllib.request.urlopen(urllib.request.Request(BASE+"/api/admin/signals?limit=1", headers=H)))
signals = r.get("data", [])
if isinstance(signals, dict):
    signals = signals.get("content", signals.get("signals", []))
sig = signals[0]
print(json.dumps(sig, indent=2, default=str)[:2000])

sid = sig.get("id")
if sid:
    print(f"\n=== Pipeline trace for {sig.get('symbol')} (id={sid}) ===")
    r2 = json.load(urllib.request.urlopen(urllib.request.Request(
        BASE+f"/api/admin/signals/{sid}/pipeline-trace", headers=H)))
    print(json.dumps(r2, indent=2, default=str)[:3000])
