import subprocess

def remote(cmd):
    r = subprocess.run([
        "ssh", "-o", "ConnectTimeout=30", "-o", "StrictHostKeyChecking=no",
        "root@173.249.55.84", cmd
    ], capture_output=True, text=True, timeout=60)
    return r.stdout + r.stderr

# 1. Check ExecutionEngine source on server
print("=== ExecutionEngine scan method ===")
print(remote("grep -n 'Scan cycle\\|processEntries\\|processExits\\|evaluate\\|scanDeployment\\|processSignal\\|saveSignal\\|daily\\|DAILY' /opt/stokr/stokr-platform/backend/src/main/java/com/stokr/engine/ExecutionEngine.java | head -40"))

print("\n=== How deployments are loaded (ACTIVE vs LIVE) ===")
print(remote("grep -n 'status\\|ACTIVE\\|LIVE\\|findAll\\|findActive\\|DeploymentStatus' /opt/stokr/stokr-platform/backend/src/main/java/com/stokr/engine/ExecutionEngine.java | head -20"))
