#!/usr/bin/env python3
import urllib.request, json, sys, urllib.error

BASE = "http://localhost:8080"

# Login
try:
    data = json.dumps({"principal": "admin", "password": "admin123"}).encode()
    req = urllib.request.Request(
        BASE + "/api/auth/login",
        data=data,
        headers={"Content-Type": "application/json"}
    )
    resp = json.load(urllib.request.urlopen(req))
    print("Login keys:", list(resp.keys()))
    token = resp.get("data", {}).get("accessToken", "")
    if token:
        print("TOKEN=" + token[:40] + "...")
    else:
        print("No accessToken in response")
        print(json.dumps(resp, indent=2)[:500])
        sys.exit(1)
except urllib.error.HTTPError as e:
    print("HTTP Error:", e.code)
    print(e.read().decode()[:500])
    sys.exit(1)

# Broker infra
req2 = urllib.request.Request(
    BASE + "/api/admin/broker-infrastructure",
    headers={"Authorization": "Bearer " + token}
)
try:
    resp2 = json.load(urllib.request.urlopen(req2))
    print("\n=== Broker Infrastructure ===")
    broker_str = json.dumps(resp2, indent=2, default=str)
    print(broker_str[:3000])
except urllib.error.HTTPError as e:
    print("Broker infra HTTP Error:", e.code)
    print(e.read().decode()[:1000])
