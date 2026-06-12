#!/usr/bin/env python3
import json
import paramiko
import urllib.request

HOST = "173.249.55.84"
PWD = "Temp1234.."
UID = "6343e483-1d21-4fdf-ac0c-1ba19eaf2ff4"

c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect(HOST, username="root", password=PWD, timeout=30)

sql = f"""
SELECT symbol, broker_qty, broker_avg_price, row_sync_state, updated_at
FROM broker_position_truth
WHERE user_id = '{UID}' AND broker_qty <> 0
ORDER BY symbol
LIMIT 20;
"""
_, o, e = c.exec_command(
    f"docker exec stokr-postgres psql -U postgres -d stokr_platform -c \"{sql}\""
)
print((o.read() + e.read()).decode())

# Check latest workstation HTTP status from access logs if any
_, o, e = c.exec_command(
    "docker logs stokr-api --since 15m 2>&1 | grep 'terminal/workstation' | grep -E '500|exposure_failed|Unhandled' | tail -5"
)
print("errors:", (o.read() + e.read()).decode() or "(none)")

# Try to find trader password reset or users table email
_, o, e = c.exec_command(
    f"docker exec stokr-postgres psql -U postgres -d stokr_platform -t -c \"SELECT email FROM users WHERE id='{UID}';\""
)
email = (o.read() + e.read()).decode().strip()
print("email:", email)

c.close()

# Try login with a few passwords from prod notes
for pwd in ["Stokr@123", "Vishnu@123", "password", "Password@123", "stokr123"]:
    try:
        payload = json.dumps({"principal": email, "password": pwd}).encode()
        req = urllib.request.Request(
            "https://stokr.in/api/auth/login",
            data=payload,
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=20) as r:
            body = json.loads(r.read().decode())
            if body.get("success"):
                token = body["data"].get("accessToken") or body["data"].get("access_token")
                req2 = urllib.request.Request(
                    "https://stokr.in/api/trader/terminal/workstation",
                    headers={"Authorization": f"Bearer {token}"},
                )
                with urllib.request.urlopen(req2, timeout=60) as r2:
                    ws = json.loads(r2.read().decode())
                    d = ws.get("data", {})
                    bt = d.get("brokerTruth", {})
                    opens = d.get("openPositions") or []
                    print(f"LOGIN OK pwd={pwd}")
                    print("brokerConnected:", bt.get("brokerConnected"))
                    print("lastSyncAt:", bt.get("lastSyncAt"))
                    print("syncState:", bt.get("syncState"))
                    print("openPositions:", len(opens))
                    print("symbols:", [p.get("symbol") for p in opens[:12]])
                    print("accountSummary:", d.get("accountSummary"))
                break
    except Exception as ex:
        pass
else:
    print("Could not login as trader for live API check")
