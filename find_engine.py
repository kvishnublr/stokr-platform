import subprocess

def remote(cmd):
    r = subprocess.run([
        "ssh", "-o", "ConnectTimeout=30", "-o", "StrictHostKeyChecking=no",
        "root@173.249.55.84", cmd
    ], capture_output=True, text=True, timeout=60)
    return r.stdout + r.stderr

print("=== Find ExecutionEngine ===")
print(remote("find /opt/stokr/ -name 'ExecutionEngine.java' 2>/dev/null"))

print("\n=== Search broadly ===")
print(remote("find /opt/stokr/ -name '*.java' 2>/dev/null | grep -i 'execut\|engine' | head -10"))
