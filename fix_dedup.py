import subprocess

def remote(cmd):
    r = subprocess.run([
        "ssh", "-o", "ConnectTimeout=30", "-o", "StrictHostKeyChecking=no",
        "root@173.249.55.84", cmd
    ], capture_output=True, text=True, timeout=60)
    return r.stdout + r.stderr

# 1. Clean up the 154 spam signals
print("=== Cleaning 154 spam signals ===")
print(remote("PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -c \"DELETE FROM strategy_signals WHERE created_at >= '2026-07-13' AND strategy_id = 21 AND status = 'REJECTED';\""))

# 2. Fix the dedup in intraday path via Python patch
print("\n=== Fixing intraday dedup in SignalProcessor.java ===")
script = r"""
import subprocess
import re

# Read the file
r = subprocess.run(["ssh", "-o", "ConnectTimeout=30", "-o", "StrictHostKeyChecking=no", "root@173.249.55.84",
    "cat /opt/stokr/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/engine/SignalProcessor.java"],
    capture_output=True, text=True, timeout=30)
src = r.stdout

# Fix the intraday dedup: change single status to list of statuses
old = '''boolean alreadyOpen = signalRepository
                        .findFirstByDeploymentIdAndSymbolAndStatusOrderByCreatedAtDesc(
                            deployment.getId(), symbol, "EXECUTED")
                        .isPresent();'''

new = '''boolean alreadyOpen = signalRepository
                        .findFirstByDeploymentIdAndSymbolAndStatusInOrderByCreatedAtDesc(
                            deployment.getId(), symbol, List.of("EXECUTED", "GENERATED", "REJECTED"))
                        .isPresent();'''

if old in src:
    src = src.replace(old, new)
    # Write to temp file
    with open(r"C:\Users\itsvi\Desktop\work_new\stokr-platform\SignalProcessor_fixed.java", "w") as f:
        f.write(src)
    print("Patched locally. Uploading...")
else:
    print("Pattern not found - checking...")
    # Print surrounding context
    lines = src.split('\n')
    for i, line in enumerate(lines):
        if 'findFirstByDeploymentIdAndSymbolAndStatusOrderByCreatedAtDesc' in line:
            for j in range(max(0,i-2), min(len(lines), i+4)):
                print(f"{j+1}: {lines[j]}")
"""
exec(script)
