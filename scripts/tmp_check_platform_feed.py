import paramiko

c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)

sql1 = """
SELECT vendor_code, connection_state, websocket_state,
       access_token_enc IS NOT NULL AS has_token,
       token_expires_at, updated_at
FROM platform_broker_feed_sessions
WHERE deleted = false
ORDER BY updated_at DESC LIMIT 3;
"""
sql2 = """
SELECT count(*) FROM platform_broker_oauth_states
WHERE consumed = false AND expires_at > now();
"""
for label, sql in [("sessions", sql1), ("pending oauth", sql2)]:
    cmd = f'docker exec stokr-postgres psql -U postgres -d stokr_platform -c "{sql.strip()}"'
    _, o, e = c.exec_command(cmd)
    print(f"=== {label} ===")
    print((o.read() + e.read()).decode())

_, o, e = c.exec_command("docker logs stokr-api --since 24h 2>&1 | grep 'zerodha.callback' | tail -8")
print("=== recent callbacks ===")
print((o.read() + e.read()).decode() or "(none)")
c.close()
