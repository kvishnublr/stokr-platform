import paramiko
c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)

queries = [
    "SELECT count(*) bindings FROM strategy_runtime_bindings;",
    """SELECT column_name FROM information_schema.columns
       WHERE table_name='signal_pipeline_audit' ORDER BY ordinal_position LIMIT 15;""",
    """SELECT status, count(*) FROM signal_pipeline_audit
       WHERE created_at >= current_date - interval '7 days'
       GROUP BY status ORDER BY count(*) DESC LIMIT 10;""",
]
for sql in queries:
    _, o, e = c.exec_command(f'docker exec stokr-postgres psql -U postgres -d stokr_platform -c "{sql}"')
    print((o.read()+e.read()).decode())
c.close()
