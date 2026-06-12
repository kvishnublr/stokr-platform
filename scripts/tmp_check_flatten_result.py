#!/usr/bin/env python3
import paramiko

c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)

def run(cmd):
    _, o, e = c.exec_command(cmd, timeout=120)
    return (o.read() + e.read()).decode()

UID = "6343e483-1d21-4fdf-ac0c-1ba19eaf2ff4"
print(run("docker logs stokr-api --since 8m 2>&1 | grep -iE 'flatten|outcome_exit.placed|outcome_exit.failed|rollback|TERMINAL_FLATTEN' | tail -50"))
print(run(f"""docker exec -i stokr-postgres psql -U postgres -d stokr_platform -c "
SELECT symbol, side, quantity, state, execution_mode, reject_reason,
       created_at AT TIME ZONE 'Asia/Kolkata' AS ist
FROM oms_orders
WHERE user_id='{UID}' AND created_at > NOW() - INTERVAL '15 minutes'
ORDER BY created_at DESC LIMIT 25;" """))
c.close()
