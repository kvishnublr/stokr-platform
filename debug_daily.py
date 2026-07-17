import subprocess

def remote(cmd):
    r = subprocess.run([
        "ssh", "-o", "ConnectTimeout=30", "-o", "StrictHostKeyChecking=no",
        "root@173.249.55.84", cmd
    ], capture_output=True, text=True, timeout=60)
    return r.stdout + r.stderr

# Check logs around 15:10-15:20
print("=== Engine logs around 15:10-15:20 ===")
print(remote("docker logs stokr-lite-backend --since 6h 2>&1 | grep -i 'daily\\|DAILY\\|15:1\\|15:2\\|processDaily\\|Error.*daily\\|candles.*size' | tail -30"))

# Check for any errors around that time
print("\n=== Errors around 15:10-15:20 ===")
print(remote("docker logs stokr-lite-backend --since 6h 2>&1 | grep -i 'ERROR\\|exception\\|Error.*process' | tail -20"))
