#!/usr/bin/env python3
"""
Zerodha Auto Token Refresh - runs via crontab daily at 8:30 AM IST
Flow: login → TOTP → OAuth connect → request_token → access_token → DB update
"""
import requests
import pyotp
import json
import hashlib
import subprocess
import sys
from datetime import datetime, timezone

API_KEY = "zazlrld244cc6jf0"
API_SECRET = "iyc7m8166tb6i95gt829q6mzbzvmfq6k"
CLIENT_ID = "DS8838"
PASSWORD = "Temp1234"
TOTP_SECRET = "BQW7QISFB4PFA7SV3VSZAQ4B5I4WJUKC"
LOG_FILE = "/var/log/stokr-token-refresh.log"

def log(msg):
    ts = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%S UTC")
    line = f"[{ts}] {msg}"
    print(line)
    with open(LOG_FILE, "a") as f:
        f.write(line + "\n")

try:
    # Step 1: Generate TOTP
    totp = pyotp.TOTP(TOTP_SECRET)
    current_totp = totp.now()
    log(f"TOTP generated: {current_totp}")

    # Step 2: Login (user_id + password)
    session = requests.Session()
    session.headers.update({"User-Agent": "Mozilla/5.0", "X-Kite-Version": "3"})

    resp = session.post("https://kite.zerodha.com/api/login", data={
        "user_id": CLIENT_ID,
        "password": PASSWORD
    })
    d = resp.json()
    if d.get("status") != "success":
        log(f"LOGIN FAILED: {d.get('message', 'unknown')}")
        sys.exit(1)
    request_id = d["data"]["request_id"]
    log(f"Login OK, request_id: {request_id[:20]}...")

    # Step 3: Submit TOTP
    current_totp = totp.now()
    resp = session.post("https://kite.zerodha.com/api/twofa", data={
        "request_id": request_id,
        "twofa_value": current_totp,
        "user_id": CLIENT_ID,
        "twofa_type": "totp"
    })
    d2 = resp.json()
    if d2.get("status") != "success":
        log(f"TOTP FAILED: {d2.get('message', 'unknown')}")
        sys.exit(1)
    log("TOTP OK")

    # Step 4: OAuth connect → request_token
    connect_url = f"https://kite.zerodha.com/connect/login?api_key={API_KEY}&v=3"
    request_token = None
    cur_url = connect_url
    for _ in range(10):
        if "request_token=" in cur_url:
            idx = cur_url.index("request_token=") + len("request_token=")
            end = cur_url.index("&", idx) if "&" in cur_url[idx:] else len(cur_url)
            request_token = cur_url[idx:end]
            break
        resp = session.get(cur_url, allow_redirects=False)
        location = resp.headers.get("Location")
        if not location:
            break
        if location.startswith("/"):
            location = "https://kite.zerodha.com" + location
        cur_url = location

    if not request_token:
        log("FAILED: no request_token obtained")
        sys.exit(1)
    log(f"request_token obtained: {request_token[:20]}...")

    # Step 5: Exchange for access_token
    checksum = hashlib.sha256((API_KEY + request_token + API_SECRET).encode()).hexdigest()
    resp = requests.post("https://api.kite.trade/session/token", data={
        "api_key": API_KEY,
        "request_token": request_token,
        "checksum": checksum
    })
    td = resp.json()
    if td.get("status") != "success":
        log(f"TOKEN EXCHANGE FAILED: {td.get('message', 'unknown')}")
        sys.exit(1)
    access_token = td["data"]["access_token"]
    log(f"access_token obtained: {access_token[:20]}...")

    # Step 6: Verify
    vr = requests.get("https://api.kite.trade/user/profile",
        headers={"Authorization": f"token {API_KEY}:{access_token}"})
    vd = vr.json()
    if vd.get("status") != "success":
        log(f"VERIFY FAILED")
        sys.exit(1)
    log(f"Verified: {vd['data'].get('user_name')} @ {vd['data'].get('broker')}")

    # Step 7: Update DB
    sql = f"""UPDATE broker_accounts 
    SET access_token = '{access_token}',
        status = 'ACTIVE',
        token_expiry = NOW() AT TIME ZONE 'UTC' + interval '1 day',
        updated_at = NOW()
    WHERE id = 1;"""
    result = subprocess.run(
        ['psql', '-h', 'localhost', '-U', 'postgres', '-d', 'stokr_lite', '-c', sql],
        capture_output=True, text=True, timeout=30,
        env={"PGPASSWORD": "stokr2026"}
    )
    if result.returncode != 0:
        log(f"DB UPDATE FAILED: {result.stderr}")
        sys.exit(1)
    log("DB updated")

    # Step 8: Restart backend to load new token into memory
    result = subprocess.run(
        ['docker', 'restart', 'stokr-lite-backend'],
        capture_output=True, text=True, timeout=60
    )
    log(f"Backend restarted: {result.stdout.strip()}")

    # Step 9: Verify quote fetch
    import time
    time.sleep(15)
    qr = requests.get("https://api.kite.trade/quote",
        headers={"Authorization": f"token {API_KEY}:{access_token}", "X-Kite-Version": "3"},
        params={"i": "NSE:RELIANCE"})
    qd = qr.json()
    if qd.get("status") == "success":
        ltp = qd["data"]["NSE:RELIANCE"]["last_price"]
        log(f"Quote verified: RELIANCE LTP = {ltp}")
    else:
        log(f"Quote verify failed: {qd.get('message', 'unknown')}")

    log("=== TOKEN REFRESH COMPLETE ===")

except Exception as e:
    log(f"ERROR: {e}")
    sys.exit(1)
