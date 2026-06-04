import paramiko
c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect('173.249.55.84', username='root', password='Temp1234..', timeout=30)

def run(cmd, t=180):
    print('\n$ ' + cmd[:300])
    _, o, e = c.exec_command(cmd, timeout=t)
    out = (o.read() + e.read()).decode('utf-8', 'replace')
    print(out[-25000:] if len(out)>25000 else out)

run('docker exec stokr-api curl -sS -m 15 http://127.0.0.1:8080/actuator/health 2>/dev/null | head -c 3000')
run('docker exec stokr-api curl -sS -m 15 http://127.0.0.1:8080/health 2>/dev/null | head -c 3000')
run('docker exec stokr-api curl -sS -m 15 http://127.0.0.1:8080/api/health 2>/dev/null | head -c 3000')
run('docker exec stokr-api curl -sS -m 15 http://127.0.0.1:8080/api/admin/operations/diagnostics 2>/dev/null | head -c 5000')
run('docker logs stokr-api 2>&1 | tail -80')
run('''docker exec stokr-postgres psql -U postgres -d stokr_platform -t -A -c "
SELECT sd.strategy_key, si.id::text, si.user_id::text, si.status, si.execution_mode
FROM strategy_instances si
JOIN strategy_definitions sd ON sd.id = si.strategy_catalog_id
WHERE sd.strategy_key = 'ADV_CASH'
ORDER BY si.updated_at DESC NULLS LAST
LIMIT 5;
"''')
run('''docker exec stokr-postgres psql -U postgres -d stokr_platform -t -A -c "
SELECT key, value FROM app_config WHERE key ILIKE '%execution%mode%' OR key ILIKE '%ADV_CASH%' OR key ILIKE '%safe%startup%' LIMIT 30;
"''')
c.close()
