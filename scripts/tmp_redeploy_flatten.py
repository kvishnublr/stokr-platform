#!/usr/bin/env python3
import json, paramiko, time
c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)
UID = "6343e483-1d21-4fdf-ac0c-1ba19eaf2ff4"

def run(cmd, t=600):
    print(f">>> {cmd[:100]}")
    _, o, e = c.exec_command(cmd, timeout=t)
    out = (o.read()+e.read()).decode()
    print(out[-2500:] if len(out)>2500 else out)
    return out

run("cd /opt/stokr/stokr-platform && git pull origin Release_v2", 120)
run("cd /opt/stokr/stokr-platform && docker compose build api", 900)
run("cd /opt/stokr/stokr-platform && docker compose up -d api", 120)
for _ in range(16):
    time.sleep(12)
    if "200" in run("curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health", 30) or "404" in run("curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/api/auth/login", 30):
        break

login = json.loads(run("""curl -s -X POST http://127.0.0.1:8080/api/auth/login -H 'Content-Type: application/json' -d '{"principal":"admin@stokr.local","password":"admin123"}'""").strip().split('\n')[-1])
admin = login["data"]["accessToken"]
print("\n=== FLATTEN ===")
print(run(f"""curl -s -X POST 'http://127.0.0.1:8080/api/admin/trader/{UID}/flatten-broker-positions' -H 'Authorization: Bearer {admin}'"""))

print("\n=== ORDERS ===")
run(f"""docker exec -i stokr-postgres psql -U postgres -d stokr_platform -c "
SELECT symbol, side, quantity, state, execution_mode, strategy_key, reject_reason,
       created_at AT TIME ZONE 'Asia/Kolkata' AS ist
FROM oms_orders WHERE user_id='{UID}' AND strategy_key='TERMINAL_FLATTEN'
  AND created_at > NOW() - INTERVAL '10 minutes'
ORDER BY created_at DESC;" """)

print("\n=== POSITION FETCH LOGS ===")
run("docker logs stokr-api --since 3m 2>&1 | grep -iE 'zerodha.positions|terminal.flatten|TERMINAL_FLATTEN|recon_fallback' | tail -25")
c.close()
