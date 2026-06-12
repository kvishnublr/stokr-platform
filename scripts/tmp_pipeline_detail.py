import paramiko
c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)
sql = """
SELECT strategy_key, symbol, rejection_code, rejection_message, created_at
FROM signal_pipeline_audit WHERE created_at >= current_date
ORDER BY created_at DESC LIMIT 30;
"""
_, o, e = c.exec_command(f'docker exec stokr-postgres psql -U postgres -d stokr_platform -c "{sql}"')
print((o.read()+e.read()).decode())

sql2 = """
SELECT strategy_name, symbol, signal_type, confidence_score, created_at
FROM strategy_signals WHERE created_at >= current_date - interval '3 days'
ORDER BY created_at DESC LIMIT 15;
"""
_, o, e = c.exec_command(f'docker exec stokr-postgres psql -U postgres -d stokr_platform -c "{sql2}"')
print("=== recent signals 3d ===")
print((o.read()+e.read()).decode())

_, o, e = c.exec_command("docker logs stokr-api --since 2h 2>&1 | grep -E 'catalog.scan.signal|persist_failed|quality|PRE_OPEN|adv_cash|nse_spike' | tail -20")
print("=== signal logs ===")
print((o.read()+e.read()).decode())
c.close()
