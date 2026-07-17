import subprocess

def remote(cmd):
    r = subprocess.run([
        "ssh", "-o", "ConnectTimeout=30", "-o", "StrictHostKeyChecking=no",
        "root@173.249.55.84", cmd
    ], capture_output=True, text=True, timeout=60)
    return r.stdout + r.stderr

# 1. Current time on server
print("=== Current server time (IST) ===")
print(remote("TZ=Asia/Kolkata date '+%Y-%m-%d %H:%M:%S %Z'"))

# 2. The critical filter - daily strategies ONLY evaluate at 15:10-15:20
print("\n=== Daily strategy time filter ===")
print(remote("grep -n 'processDailyDeployment\\|15.*10\\|15.*20\\|hour.*15\\|EOD\\|isDailyStrategy' /opt/stokr/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/engine/SignalProcessor.java | head -10"))

# 3. Check what 'isDailyStrategy' does in ExecutionEngine
print("\n=== isDailyStrategy method ===")
print(remote("grep -n -A5 'isDailyStrategy' /opt/stokr/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/engine/ExecutionEngine.java"))

# 4. Were there signals Jul 8-11?
print("\n=== Signals from Jul 8-11 ===")
print(remote("PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -c \"SELECT id, symbol, strategy_id, status, created_at FROM strategy_signals WHERE created_at >= '2026-07-08' ORDER BY created_at;\""))

# 5. Check strategies timeframe in DB
print("\n=== Strategy timeframes ===")
print(remote("PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -c \"SELECT id, name, timeframe, enabled FROM strategies WHERE enabled=true;\""))
