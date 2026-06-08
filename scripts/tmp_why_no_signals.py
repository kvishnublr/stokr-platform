import paramiko
from collections import Counter

c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)

def q(sql):
    s = " ".join(sql.split())
    _, o, e = c.exec_command(f'docker exec stokr-postgres psql -U postgres -d stokr_platform -c "{s}"')
    return (o.read() + e.read()).decode()

print("=== TIME ===")
_, o, _ = c.exec_command("TZ=Asia/Kolkata date")
print(o.read().decode())

print("=== Runtime health today ===")
print(q("""
SELECT strategy_name, execution_mode, scans_attempted, scans_blocked_integrity,
       scans_blocked_feed, signals_generated, last_signal_time, last_rejection_reason
FROM strategy_runtime_health WHERE session_date = current_date
ORDER BY scans_attempted DESC;
"""))

print("=== Signals today ===")
print(q("""
SELECT strategy_name, count(*), max(created_at)
FROM strategy_signals WHERE created_at >= current_date AND deleted = false
GROUP BY strategy_name;
"""))

print("=== Pipeline rejections today (top reasons) ===")
print(q("""
SELECT strategy_key, rejection_code, count(*)
FROM signal_pipeline_audit
WHERE created_at >= current_date AND rejection_code IS NOT NULL
GROUP BY strategy_key, rejection_code
ORDER BY count(*) DESC LIMIT 25;
"""))

print("=== Pipeline stages today ===")
print(q("""
SELECT strategy_key, pipeline_stage, execution_status, count(*)
FROM signal_pipeline_audit WHERE created_at >= current_date
GROUP BY strategy_key, pipeline_stage, execution_status
ORDER BY count(*) DESC LIMIT 20;
"""))

print("=== Recent catalog scan logs (45m) ===")
_, o, e = c.exec_command(
    "docker logs stokr-api --since 45m 2>&1 | "
    "grep -E 'catalog.scan.binding_done|integrity.block|signal.persist|pipeline.reject|quality.gate|session.guard' | tail -40"
)
logs = (o.read() + e.read()).decode().splitlines()
print("\n".join(logs[-40:]) if logs else "(none)")

# Parse binding_done lines for block reasons
reasons = Counter()
for line in logs:
    if "binding_done" in line:
        if "integrityBlocked=" in line:
            parts = line.split("integrityBlocked=")[1]
            ib = parts.split()[0]
            sk = line.split("strategyKey=")[1].split()[0] if "strategyKey=" in line else "?"
            if ib != "0":
                reasons[(sk, "integrityBlocked", ib)] += 1
            elif "evaluated=0" in line and "signals=0" in line:
                reasons[(sk, "evaluated_zero", "hold_or_gates")] += 1

print("\n=== Parsed scan summary from logs ===")
for k, v in reasons.most_common(15):
    print(f"{k}: {v}")

c.close()
