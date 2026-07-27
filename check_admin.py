import subprocess, json, time

def remote(cmd):
    r = subprocess.run([
        "ssh", "-o", "ConnectTimeout=30", "-o", "StrictHostKeyChecking=no",
        "root@173.249.55.84", cmd
    ], capture_output=True, text=True, timeout=600)
    return r.stdout + r.stderr

# Check admin user
print("=== Admin user in DB ===")
print(remote("PGPASSWORD=`$POSTGRES_PASSWORD psql -h localhost -U postgres -d stokr_lite -c \"SELECT id, email, role FROM users WHERE role='ADMIN';\""))

# Use trader token but write a Python script that uses Zerodha API directly
print("\n=== Writing standalone backfill script ===")

