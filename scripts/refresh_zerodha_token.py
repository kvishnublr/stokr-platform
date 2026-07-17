#!/usr/bin/env python3
"""Reinitialize Zerodha token - correct 2-step flow"""
import requests
import pyotp
import json
import hashlib
import subprocess
import time

API_KEY = "zazlrld244cc6jf0"
API_SECRET = "iyc7m8166tb6i95gt829q6mzbzvmfq6k"
CLIENT_ID = "DS8838"
PASSWORD = "Temp1234"
TOTP_SECRET = "BQW7QISFB4PFA7SV3VSZAQ4B5I4WJUKC"

totp = pyotp.TOTP(TOTP_SECRET)
current_totp = totp.now()
print(f"[1] TOTP: {current_totp}")

session = requests.Session()
session.headers.update({"User-Agent": "Mozilla/5.0", "X-Kite-Version": "3"})

# Step 1: user_id + password only (NO totp)
print(f"[2] Step 1: user_id + password...")
resp = session.post("https://api.kite.trade/api/login", data={
    "user_id": CLIENT_ID,
    "password": PASSWORD
})
d = resp.json()
print(f"    {json.dumps(d, indent=2)}")

if d.get("status") != "success":
    print("Step 1 failed!")
    exit(1)

request_id = d["data"]["request_id"]
print(f"    request_id: {request_id}")
time.sleep(1)

# Step 2: request_id + totp
print(f"[3] Step 2: request_id + totp...")
totp_now = totp.now()
print(f"    TOTP: {totp_now}")
resp = session.post("https://api.kite.trade/api/login", data={
    "request_id": request_id,
    "totp": totp_now
})
d2 = resp.json()
print(f"    {json.dumps(d2, indent=2)}")

request_token = None
if d2.get("status") == "success":
    request_token = d2["data"].get("request_token")
    
if not request_token:
    # Try with user_id as well
    print(f"[3b] Retrying with user_id + request_id + totp...")
    resp = session.post("https://api.kite.trade/api/login", data={
        "user_id": CLIENT_ID,
        "request_id": request_id,
        "totp": totp_now
    })
    d2b = resp.json()
    print(f"    {json.dumps(d2b, indent=2)}")
    if d2b.get("status") == "success":
        request_token = d2b["data"].get("request_token")

if not request_token:
    print("\nFAILED to get request_token")
    exit(1)

print(f"    request_token: {request_token}")

# Step 3: Exchange for access_token
checksum_str = API_KEY + request_token + API_SECRET
checksum = hashlib.sha256(checksum_str.encode()).hexdigest()

print(f"[4] Exchanging for access_token...")
resp = session.post("https://api.kite.trade/api/session/token", data={
    "api_key": API_KEY,
    "request_token": request_token,
    "checksum": checksum
})
td = resp.json()
print(f"    {json.dumps(td, indent=2)}")

if td.get("status") != "success":
    print("Token exchange failed!")
    exit(1)

access_token = td["data"]["access_token"]
print(f"\n[5] Access token: {access_token}")

# Step 4: Verify
print(f"[6] Verifying...")
vr = requests.get("https://api.kite.trade/user/profile",
    headers={"Authorization": f"token {API_KEY}:{access_token}"})
vd = vr.json()
if vd.get("status") == "success":
    u = vd["data"]
    print(f"[6] OK! {u.get('user_name')} @ {u.get('broker')}")
else:
    print(f"[6] VERIFY FAILED")
    exit(1)

# Step 5: Update DB
print(f"[7] Updating DB...")
sql = f"""UPDATE broker_accounts 
SET access_token = '{access_token}',
    status = 'ACTIVE',
    token_expiry = NOW() AT TIME ZONE 'UTC' + interval '1 day',
    updated_at = NOW()
WHERE id = 1;"""

result = subprocess.run(
    ['ssh', 'root@173.249.55.84', f"PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -c \"{sql}\""],
    capture_output=True, text=True, timeout=30
)
print(result.stdout)

print(f"\n[DONE] Token refreshed!")
