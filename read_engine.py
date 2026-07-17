import subprocess

def remote(cmd):
    r = subprocess.run([
        "ssh", "-o", "ConnectTimeout=30", "-o", "StrictHostKeyChecking=no",
        "root@173.249.55.84", cmd
    ], capture_output=True, text=True, timeout=60)
    return r.stdout + r.stderr

# Read the full scan cycle method
print("=== ExecutionEngine - scan cycle and deployment processing ===")
print(remote("grep -n '' /opt/stokr/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/engine/ExecutionEngine.java | sed -n '1,50p'"))

print("\n=== Look for signal generation logic ===")
print(remote("grep -n 'processSignal\\|saveSignal\\|Signal\\|signal\\|signalRepo\\|strategySignal' /opt/stokr/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/engine/ExecutionEngine.java"))
