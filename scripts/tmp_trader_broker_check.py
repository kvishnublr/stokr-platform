import paramiko, json
c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)

queries = [
    "SELECT id, email, display_name FROM auth_users WHERE deleted=false ORDER BY updated_at DESC LIMIT 10;",
    "SELECT column_name FROM information_schema.columns WHERE table_name='broker_accounts' ORDER BY ordinal_position;",
    """SELECT ba.user_id, u.email, ba.vendor_code, ba.connection_state, ba.access_token_enc IS NOT NULL AS has_token,
       ba.token_expires_at, ba.updated_at
FROM broker_accounts ba
JOIN auth_users u ON u.id = ba.user_id
WHERE ba.deleted = false ORDER BY ba.updated_at DESC LIMIT 10;""",
    """SELECT pp.user_id, u.email, pp.symbol, pp.quantity, pp.updated_at
FROM portfolio_positions pp
JOIN auth_users u ON u.id = pp.user_id
WHERE pp.deleted=false AND pp.quantity != 0
ORDER BY pp.updated_at DESC LIMIT 15;""",
]
for sql in queries:
    s = " ".join(sql.split())
    _, o, e = c.exec_command(f'docker exec stokr-postgres psql -U postgres -d stokr_platform -c "{s}"')
    print((o.read()+e.read()).decode())
    print("---")
c.close()
