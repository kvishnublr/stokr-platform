#!/usr/bin/env python3
import paramiko, base64
HOST='173.249.55.84'
def run(cmd):
    c=paramiko.SSHClient();c.set_missing_host_key_policy(paramiko.AutoAddPolicy());c.connect(HOST,username='root',password='Temp1234..',timeout=30)
    _,o,e=c.exec_command(cmd,timeout=90); r=(o.read()+e.read()).decode(); c.close(); return r
def psql(sql):
    b64=base64.b64encode(sql.encode()).decode()
    return run(f'echo {b64} | base64 -d | docker exec -i stokr-postgres psql -U postgres -d stokr_platform')

print('=== OMS COLUMNS ===')
print(psql("SELECT column_name FROM information_schema.columns WHERE table_name='oms_orders' AND column_name IN ('signal_id','execution_mode','state','reject_reason') ORDER BY 1;"))

print('\n=== LIVE signal_id today ===')
print(psql("""
SELECT COUNT(*) FILTER (WHERE signal_id IS NULL) as null_sig,
       COUNT(*) FILTER (WHERE signal_id IS NOT NULL) as has_sig,
       COUNT(*) as total
FROM oms_orders WHERE execution_mode='LIVE' AND state='FILLED'
AND created_at >= (CURRENT_DATE AT TIME ZONE 'Asia/Kolkata')::timestamptz;
"""))

print('\n=== FLYWAY tail ===')
print(psql('SELECT version, description FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 10;'))

print('\n=== JAR AdminHealth ===')
print(run('docker exec stokr-api sh -c "jar tf /app/app.jar 2>/dev/null | grep -E AdminHealth|AdminDashboard|io/stokr" | head -25'))

print('\n=== MANUAL signals ===')
print(psql("""
SELECT COUNT(*) FROM strategy_signals
WHERE created_at >= (CURRENT_DATE AT TIME ZONE 'Asia/Kolkata')::timestamptz
AND outcome_status='MANUAL';
"""))

print('\n=== HTTP codes unauth ===')
for p in ['/api/admin/diagnostics/health','/api/admin/diagnostics/timeline','/actuator/mappings']:
    print(p, run(f'curl -s -o /dev/null -w "%{{http_code}}" http://127.0.0.1:8080{p}'))
