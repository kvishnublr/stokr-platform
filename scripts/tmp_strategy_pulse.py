import paramiko
from datetime import datetime

c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)

queries = {
    "signals_today": """
SELECT strategy_key, count(*) signals, max(created_at) last_at
FROM strategy_signals
WHERE created_at >= current_date
GROUP BY strategy_key ORDER BY signals DESC;
""",
    "orders_today": """
SELECT strategy_key, count(*) orders
FROM oms_orders
WHERE created_at >= current_date
GROUP BY strategy_key ORDER BY orders DESC;
""",
    "runtime_health": """
SELECT strategy_key, scans, integrity_blocked, signals_generated, updated_at
FROM strategy_runtime_health
WHERE trading_date = current_date
ORDER BY scans DESC LIMIT 8;
""",
    "unreconciled": """
SELECT strategy_key, count(*) cnt
FROM strategy_signals
WHERE status IN ('AWAITING_CLOSE','UNRECONCILED_TRADE')
  AND created_at >= current_date - interval '7 days'
GROUP BY strategy_key ORDER BY cnt DESC LIMIT 6;
""",
}

for label, sql in queries.items():
    cmd = f'docker exec stokr-postgres psql -U postgres -d stokr_platform -c "{sql.strip()}"'
    _, o, e = c.exec_command(cmd)
    out = (o.read() + e.read()).decode().strip()
    print(f"=== {label} ===")
    print(out if out else "(empty)")
    print()

_, o, e = c.exec_command("date -u; curl -sf http://127.0.0.1:8080/actuator/health | head -c 80")
print("=== meta ===")
print((o.read() + e.read()).decode())
c.close()
