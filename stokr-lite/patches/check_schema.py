import subprocess
p = subprocess.run(
    ['psql', '-h', 'localhost', '-U', 'postgres', '-d', 'stokr_lite', '-c',
     "\\d broker_accounts"],
    capture_output=True, text=True, env={'PGPASSWORD': 'stokr2026'}
)
print(p.stdout)
