import subprocess
p = subprocess.run(
    ['psql', '-h', 'localhost', '-U', 'postgres', '-d', 'stokr_lite', '-c',
     "SELECT id, broker, client_id, left(access_token, 20) as token_start, token_expiry, is_active FROM broker_accounts ORDER BY id;"],
    capture_output=True, text=True, env={'PGPASSWORD': 'stokr2026'}
)
print(p.stdout)
if p.stderr:
    print(p.stderr)
