#!/usr/bin/env python3
"""Full Zerodha token refresh - runs directly on server"""
import requests
import pyotp
import json
import hashlib
import subprocess
from datetime import datetime, timezone

API_KEY = "`$ZERODHA_API_KEY"
API_SECRET = "`$ZERODHA_API_SECRET"
CLIENT_ID = "DS8838"
PASSWORD = "`$ZERODHA_CLIENT_PASSWORD"
TOTP_SECRET = "`$ZERODHA_TOTP_SECRET"

# Step 1: Generate TOTP
totp = pyotp.TOTP(TOTP_SECRET)
current_totp = totp.now()
print(f"[1] TOTP: {current_totp}")

# Step 2: Login to get request_id (user_id + password only)
session = requests.Session()
session.headers.update({"User-Agent": "Mozilla/5.0", "X-Kite-Version": "3"})

print(f"[2] Step 1: Login...")
resp = session.post("https://kite.zerodha.com/api/login", data={
    "user_id": CLIENT_ID,
    "password": PASSWORD
})
d = resp.json()
print(f"    Status: {d.get('status')}")
if d.get("status") != "success":
    print(f"    FAILED: {json.dumps(d, indent=2)}")
    exit(1)
request_id = d["data"]["request_id"]
print(f"    request_id: {request_id}")

# Step 3: Submit TOTP via /api/twofa
current_totp = totp.now()
print(f"[3] Step 2: TOTP ({current_totp})...")
resp = session.post("https://kite.zerodha.com/api/twofa", data={
    "request_id": request_id,
    "twofa_value": current_totp,
    "user_id": CLIENT_ID,
    "twofa_type": "totp"
})
d2 = resp.json()
print(f"    Status: {d2.get('status')}")
if d2.get("status") != "success":
    print(f"    FAILED: {json.dumps(d2, indent=2)}")
    exit(1)
print(f"    2FA OK")

# Step 4: Hit connect/login to trigger OAuth redirect and get request_token
print(f"[4] Step 3: OAuth connect...")
connect_url = f"https://kite.zerodha.com/connect/login?api_key={API_KEY}&v=3"
resp = session.get(connect_url, allow_redirects=False)
print(f"    Status: {resp.status_code}")

# Follow redirects manually
max_hops = 10
cur_url = connect_url
while max_hops > 0:
    max_hops -= 1
    # Check if request_token is in the URL
    if "request_token=" in cur_url:
        idx = cur_url.index("request_token=") + len("request_token=")
        end = cur_url.index("&", idx) if "&" in cur_url[idx:] else len(cur_url)
        request_token = cur_url[idx:end]
        print(f"    Got request_token: {request_token}")
        break
    
    resp = session.get(cur_url, allow_redirects=False)
    location = resp.headers.get("Location")
    if not location:
        print(f"    No redirect. Status: {resp.status_code}, URL: {cur_url[:200]}")
        print(f"    Body: {resp.text[:300]}")
        break
    print(f"    {resp.status_code} -> {location[:120]}...")
    cur_url = location
    if cur_url.startswith("/"):
        cur_url = "https://kite.zerodha.com" + cur_url
else:
    print("    FAILED: too many hops")
    exit(1)

if not request_token:
    print("FAILED: no request_token")
    exit(1)

# Step 5: Exchange for access_token
checksum_str = API_KEY + request_token + API_SECRET
checksum = hashlib.sha256(checksum_str.encode()).hexdigest()

print(f"[5] Exchanging for access_token...")
resp = requests.post("https://api.kite.trade/session/token", data={
    "api_key": API_KEY,
    "request_token": request_token,
    "checksum": checksum
})
td = resp.json()
if td.get("status") != "success":
    print(f"FAILED: {json.dumps(td, indent=2)}")
    exit(1)

access_token = td["data"]["access_token"]
print(f"    access_token: {access_token}")

# Step 6: Verify
print(f"[6] Verifying...")
vr = requests.get("https://api.kite.trade/user/profile",
    headers={"Authorization": f"token {API_KEY}:{access_token}"})
vd = vr.json()
if vd.get("status") == "success":
    u = vd["data"]
    print(f"    OK! {u.get('user_name')} @ {u.get('broker')}")
else:
    print(f"    FAILED: {json.dumps(vd, indent=2)}")
    exit(1)

# Step 7: Update DB
print(f"[7] Updating DB...")
sql = f"""UPDATE broker_accounts 
SET access_token = '{access_token}',
    status = 'ACTIVE',
    token_expiry = NOW() AT TIME ZONE 'UTC' + interval '1 day',
    updated_at = NOW()
WHERE id = 1;"""

result = subprocess.run(
    ['psql', '-h', 'localhost', '-U', 'postgres', '-d', 'stokr_lite', '-c', sql],
    capture_output=True, text=True, timeout=30,
    env={"PGPASSWORD": "`$POSTGRES_PASSWORD"}
)
print(result.stdout)

# Step 8: Test a quote
print(f"[8] Testing quote fetch...")
qr = requests.get("https://api.kite.trade/quote",
    headers={"Authorization": f"token {API_KEY}:{access_token}", "X-Kite-Version": "3"},
    params={"i": "NSE:RELIANCE"})
qd = qr.json()
if qd.get("status") == "success":
    ltp = qd["data"]["NSE:RELIANCE"]["last_price"]
    print(f"    RELIANCE LTP: {ltp}")
else:
    print(f"    Quote failed: {json.dumps(qd, indent=2)}")

print(f"\n[DONE] Token refreshed successfully!")

