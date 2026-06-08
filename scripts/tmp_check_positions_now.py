#!/usr/bin/env python3
import json
import paramiko

c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)

def run(cmd):
    _, o, e = c.exec_command(cmd, timeout=120)
    return (o.read() + e.read()).decode()

UID = "6343e483-1d21-4fdf-ac0c-1ba19eaf2ff4"

login = run("""curl -s -X POST http://127.0.0.1:8080/api/auth/login -H 'Content-Type: application/json' -d '{"principal":"admin@stokr.local","password":"admin123"}'""")
token = json.loads(login)["data"]["accessToken"]

print("=== flatten-broker-positions ===")
print(run(f"""curl -s -X POST 'http://127.0.0.1:8080/api/admin/trader/{UID}/flatten-broker-positions' -H 'Authorization: Bearer {token}'"""))

print("\n=== tables like broker ===")
print(run("""docker exec -i stokr-postgres psql -U postgres -d stokr_platform -c "\\dt *broker*" """))

print("\n=== portfolio_positions non-zero ===")
print(run(f"""docker exec -i stokr-postgres psql -U postgres -d stokr_platform -c "
SELECT symbol, quantity, avg_price, updated_at AT TIME ZONE 'Asia/Kolkata'
FROM portfolio_positions
WHERE user_id='{UID}' AND deleted=false AND quantity <> 0
ORDER BY symbol;" """))

print("\n=== active orders ===")
print(run(f"""docker exec -i stokr-postgres psql -U postgres -d stokr_platform -c "
SELECT symbol, side, quantity, state, execution_mode, strategy_key, idempotency_key, reject_reason
FROM oms_orders
WHERE user_id='{UID}'
  AND state IN ('CREATED','VALIDATED','RISK_CHECK','PENDING_SUBMISSION','SUBMITTED','ACCEPTED','PARTIALLY_FILLED')
ORDER BY symbol, side;" """))

print("\n=== recent flatten/outcome orders 2h ===")
print(run(f"""docker exec -i stokr-postgres psql -U postgres -d stokr_platform -c "
SELECT symbol, side, quantity, state, execution_mode, strategy_key, reject_reason,
       created_at AT TIME ZONE 'Asia/Kolkata' AS ist
FROM oms_orders
WHERE user_id='{UID}'
  AND (strategy_key LIKE 'TERMINAL%' OR idempotency_key LIKE 'outcome-exit:%' OR idempotency_key LIKE 'terminal:%')
  AND created_at > NOW() - INTERVAL '2 hours'
ORDER BY created_at DESC LIMIT 50;" """))

print("\n=== outcome-exit summary 24h ===")
print(run(f"""docker exec -i stokr-postgres psql -U postgres -d stokr_platform -c "
SELECT execution_mode, state, count(*)
FROM oms_orders
WHERE user_id='{UID}' AND idempotency_key LIKE 'outcome-exit:%'
  AND created_at > NOW() - INTERVAL '24 hours'
GROUP BY execution_mode, state
ORDER BY 1,2;" """))

c.close()
