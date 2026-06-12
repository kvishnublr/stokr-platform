#!/usr/bin/env python3
import json, paramiko, time
c=paramiko.SSHClient(); c.set_missing_host_key_policy(paramiko.AutoAddPolicy()); c.connect('173.249.55.84',username='root',password='Temp1234..',timeout=30)
UID='6343e483-1d21-4fdf-ac0c-1ba19eaf2ff4'
def run(cmd,t=120):
    _,o,e=c.exec_command(cmd,timeout=t); return (o.read()+e.read()).decode()
time.sleep(45)
login=json.loads(run("""curl -s -X POST http://127.0.0.1:8080/api/auth/login -H 'Content-Type: application/json' -d '{"principal":"admin@stokr.local","password":"admin123"}'"""))
admin=login["data"]["accessToken"]
print(run(f"""curl -s -X POST 'http://127.0.0.1:8080/api/admin/trader/{UID}/flatten-broker-positions' -H 'Authorization: Bearer {admin}'""")[:4000])
print(run(f"""docker exec -i stokr-postgres psql -U postgres -d stokr_platform -c "
SELECT symbol, side, state, execution_mode, reject_reason FROM oms_orders
WHERE user_id='{UID}' AND strategy_key='TERMINAL_FLATTEN' AND created_at > NOW() - INTERVAL '5 minutes'
ORDER BY created_at DESC LIMIT 10;" """))
c.close()
