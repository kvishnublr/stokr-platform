import subprocess

def remote(cmd):
    r = subprocess.run([
        "ssh", "-o", "ConnectTimeout=30", "-o", "StrictHostKeyChecking=no",
        "root@173.249.55.84", cmd
    ], capture_output=True, text=True, timeout=30)
    print(r.stdout if r.stdout else r.stderr)

print("=== Check NUMERIC(12,4) columns across all tables ===")
remote("""PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -c "SELECT table_name, column_name, numeric_precision, numeric_scale FROM information_schema.columns WHERE numeric_precision=12 AND numeric_scale=4 ORDER BY table_name;" """)

print("\n=== Check if zerodha_token_refresh.py ran today ===")
remote("ls -la /var/log/stokr-token-refresh.log 2>&1 || echo 'LOG FILE MISSING'")

print("\n=== Check token refresh script exists ===")
remote("ls -la /usr/local/bin/zerodha_token_refresh.py 2>&1")

print("\n=== Check if prod_ops handles token refresh ===")
remote("head -50 /opt/stokr/stokr-platform/scripts/prod_ops.sh 2>&1")
