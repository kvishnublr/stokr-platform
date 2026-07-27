import subprocess

def remote(cmd):
    r = subprocess.run([
        "ssh", "-o", "ConnectTimeout=30", "-o", "StrictHostKeyChecking=no",
        "root@173.249.55.84", cmd
    ], capture_output=True, text=True, timeout=30)
    return r.stdout + r.stderr

print(remote("PGPASSWORD=`$POSTGRES_PASSWORD psql -h localhost -U postgres -d stokr_lite -t -A -c \"SELECT count(*) FROM zerodha_instruments;\""))
print("---")
print(remote("PGPASSWORD=`$POSTGRES_PASSWORD psql -h localhost -U postgres -d stokr_lite -t -A -c \"SELECT count(*) FROM universe_group_members;\""))
print("---")
print(remote("PGPASSWORD=`$POSTGRES_PASSWORD psql -h localhost -U postgres -d stokr_lite -c \"SELECT group_key FROM universe_groups;\""))
print("---")
print(remote("PGPASSWORD=`$POSTGRES_PASSWORD psql -h localhost -U postgres -d stokr_lite -c \"SELECT symbol, instrument_token FROM zerodha_instruments LIMIT 3;\""))

