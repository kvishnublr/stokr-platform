import subprocess

def remote(cmd):
    r = subprocess.run([
        "ssh", "-o", "ConnectTimeout=30", "-o", "StrictHostKeyChecking=no",
        "root@173.249.55.84", cmd
    ], capture_output=True, text=True, timeout=60)
    return r.stdout + r.stderr

# Full scan cycle
print("=== ExecutionEngine full scan cycle (lines 44-80) ===")
print(remote("sed -n '44,80p' /opt/stokr/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/engine/ExecutionEngine.java"))

print("\n=== SignalProcessor ===")
print(remote("find /opt/stokr/ -name 'SignalProcessor.java' 2>/dev/null"))
