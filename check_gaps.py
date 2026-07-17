import subprocess

def remote(cmd):
    r = subprocess.run([
        "ssh", "-o", "ConnectTimeout=30", "-o", "StrictHostKeyChecking=no",
        "root@173.249.55.84", cmd
    ], capture_output=True, text=True, timeout=120)
    return r.stdout + r.stderr

# Check if Zerodha has today's daily candle
print("=== Check daily candle data gaps ===")
print(remote("PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -c \"SELECT timestamp::date as day, count(*) as symbols FROM candle_data WHERE timeframe='daily' AND timestamp >= '2026-07-01' GROUP BY day ORDER BY day;\""))

# Check if there's a backfill endpoint or script
print("\n=== Check for backfill endpoints ===")
print(remote("grep -rn 'backfill\\|Backfill\\|fetchDaily\\|syncDaily' /opt/stokr/stokr-platform/stokr-lite/backend/src/main/java/ 2>/dev/null | head -10"))
