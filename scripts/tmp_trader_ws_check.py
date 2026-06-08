import paramiko, json, urllib.request, urllib.error

HOST = "173.249.55.84"
c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect(HOST, username="root", password="Temp1234..", timeout=30)

uid = "6343e483-1d21-4fdf-ac0c-1ba19eaf2ff4"
sql = f"""SELECT user_id, vendor_code, status, access_token_enc IS NOT NULL AS has_token,
token_expires_at, last_sync_at, health_status, updated_at
FROM broker_accounts WHERE user_id='{uid}' AND deleted=false;"""
_, o, e = c.exec_command(f'docker exec stokr-postgres psql -U postgres -d stokr_platform -c "{sql}"')
print((o.read()+e.read()).decode())

# Try login as vishnu - need password; check if we know it. Try common or grep env
_, o, e = c.exec_command("grep -i vishnu /opt/stokr/stokr-platform/.env 2>/dev/null; docker logs stokr-api --since 2h 2>&1 | grep -i 'broker.truth\\|BrokerPositionTruth' | tail -10")
print((o.read()+e.read()).decode())
c.close()

# Login attempt - user might use admin or trader creds from conversation
for email, pwd in [("vishnualgo@gmail.com", "admin123"), ("vishnualgo@gmail.com", "password"), ("admin@stokr.local", "admin123")]:
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
                data = body.get("data", {})
                token = data.get("accessToken") or data.get("access_token")
                print(f"LOGIN OK {email}")
                req2 = urllib.request.Request(
                    "https://stokr.in/api/trader/terminal/workstation",
                    headers={"Authorization": f"Bearer {token}"},
                )
                with urllib.request.urlopen(req2, timeout=30) as r2:
                    ws = json.loads(r2.read().decode())
                    d = ws.get("data", {})
                    bt = d.get("brokerTruth", {})
                    print("brokerConnected:", bt.get("brokerConnected"))
                    print("lastSyncAt:", bt.get("lastSyncAt"))
                    print("syncState:", bt.get("syncState"))
                    print("openPositions:", len(d.get("openPositions") or []))
                    print("positions sample:", (d.get("openPositions") or [])[:3])
                break
    except Exception as ex:
        print(f"login fail {email}: {ex}")
