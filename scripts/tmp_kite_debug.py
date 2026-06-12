#!/usr/bin/env python3
import paramiko
c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)

def run(cmd, t=120):
    _, o, e = c.exec_command(cmd, timeout=t)
    return (o.read()+e.read()).decode()

print("=== positions.fetched debug logs ===")
print(run("docker logs stokr-api --since 25m 2>&1 | grep -iE 'zerodha.positions|fetch_failed|getPositions|positions.fetched' | tail -30"))

print("\n=== ORPHAN vs GHOST counts by minute ===")
UID = "6343e483-1d21-4fdf-ac0c-1ba19eaf2ff4"
print(run(f"""docker exec -i stokr-postgres psql -U postgres -d stokr_platform -c "
SELECT date_trunc('minute', created_at AT TIME ZONE 'Asia/Kolkata') AS minute,
       discrepancy_type, count(*)
FROM reconciliation_events
WHERE user_id='{UID}' AND created_at > NOW() - INTERVAL '30 minutes'
GROUP BY 1,2 ORDER BY 1 DESC, 2;" """))

# Actuator or broker orchestration
print("\n=== admin broker orchestration ===")
import json
login = json.loads(run("""curl -s -X POST http://127.0.0.1:8080/api/auth/login -H 'Content-Type: application/json' -d '{"principal":"admin@stokr.local","password":"admin123"}'"""))
admin = login["data"]["accessToken"]
print(run(f"curl -s -H 'Authorization: Bearer {admin}' 'http://127.0.0.1:8080/api/admin/brokers/orchestration/users/{UID}/zerodha/positions'")[:3000])

c.close()
