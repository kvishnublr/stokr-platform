#!/usr/bin/env python3
import requests, json

BASE = "http://173.249.55.84:8081"

# Step 1: Login
r = requests.post(f"{BASE}/api/auth/login", json={"email": "admin@stokr.in", "password": "Temp@12345678"})
if r.status_code != 200:
    print(f"Login failed: {r.status_code} {r.text}")
    exit(1)
jwt = r.json()["accessToken"]
print(f"JWT: {jwt[:30]}...")

# Step 2: Trigger auto-reconnect
headers = {"Authorization": f"Bearer {jwt}", "Content-Type": "application/json"}
r = requests.post(f"{BASE}/api/brokers/zerodha/auto-reconnect/trigger", headers=headers)
print(f"Trigger: {r.status_code} {r.text}")

# Step 3: Check token status
r = requests.get(f"{BASE}/api/brokers/health", headers=headers)
print(f"Health: {r.status_code} {r.text}")
