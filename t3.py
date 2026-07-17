import subprocess

def remote(cmd):
    r = subprocess.run([
        "ssh", "-o", "ConnectTimeout=30", "-o", "StrictHostKeyChecking=no",
        "root@173.249.55.84", cmd
    ], capture_output=True, text=True, timeout=30)
    return r.stdout + r.stderr

print(remote("PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -t -A -c \"SELECT tablename FROM pg_tables WHERE tablename LIKE '%instrument%' OR tablename LIKE '%universe%member%' OR tablename LIKE '%universe%group%member%';\""))
print("---")
print(remote("PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -t -A -c \"SELECT tablename FROM pg_tables WHERE tablename LIKE '%universe%';\""))
print("---")
print(remote("PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -t -A -c \"SELECT tablename FROM pg_tables WHERE tablename LIKE '%instrument%';\""))
