#!/usr/bin/env python3
import urllib.request, json, sys, urllib.error

BASE = "http://localhost:8080"

# Login
data = json.dumps({"principal": "admin", "password": "admin123"}).encode()
req = urllib.request.Request(BASE + "/api/auth/login", data=data, headers={"Content-Type": "application/json"})
resp = json.load(urllib.request.urlopen(req))
token = resp["data"]["accessToken"]
print("Logged in")

# Try refresh
try:
    req2 = urllib.request.Request(
        BASE + "/api/admin/broker-infrastructure/ZERODHA/refresh",
        data=b"",
        headers={"Authorization": "Bearer " + token}
    )
    resp2 = json.load(urllib.request.urlopen(req2))
    print("Refresh result:", json.dumps(resp2, indent=2)[:500])
except urllib.error.HTTPError as e:
    body = e.read().decode()[:500]
    print("Refresh FAILED:", e.code, body)

# Get OAuth URL
try:
    req3 = urllib.request.Request(
        BASE + "/api/admin/broker-infrastructure/ZERODHA/connect",
        data=b"",
        headers={"Authorization": "Bearer " + token}
    )
    resp3 = json.load(urllib.request.urlopen(req3))
    print("\nConnect result:")
    print(json.dumps(resp3, indent=2)[:1000])
except urllib.error.HTTPError as e:
    body = e.read().decode()[:500]
    print("Connect FAILED:", e.code, body)
