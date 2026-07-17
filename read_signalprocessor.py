import subprocess

def remote(cmd):
    r = subprocess.run([
        "ssh", "-o", "ConnectTimeout=30", "-o", "StrictHostKeyChecking=no",
        "root@173.249.55.84", cmd
    ], capture_output=True, text=True, timeout=60)
    return r.stdout + r.stderr

print("=== SignalProcessor full source ===")
print(remote("cat /opt/stokr/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/engine/SignalProcessor.java"))
