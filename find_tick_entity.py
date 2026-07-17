import subprocess

def remote(cmd):
    r = subprocess.run([
        "ssh", "-o", "ConnectTimeout=30", "-o", "StrictHostKeyChecking=no",
        "root@173.249.55.84", cmd
    ], capture_output=True, text=True, timeout=30)
    return r.stdout

print("=== Find JPA entity for tick_anomalies ===")
print(remote("grep -rn 'tick_anomal' /opt/stokr/stokr-platform/backend/src/main/java/ 2>/dev/null | head -5"))

print("\n=== Find @Column(precision in tick entity ===")
print(remote("find /opt/stokr/stokr-platform/backend/src/main/java/ -name '*ick*' -exec grep -ln 'precision' {} \\; 2>/dev/null"))
