import subprocess, json

def remote(cmd):
    r = subprocess.run([
        "ssh", "-o", "ConnectTimeout=30", "-o", "StrictHostKeyChecking=no",
        "root@173.249.55.84", cmd
    ], capture_output=True, text=True, timeout=120)
    return r.stdout + r.stderr

# Check if Jul 13 daily candles exist
print("=== Jul 13 daily candle check ===")
print(remote("PGPASSWORD=`$POSTGRES_PASSWORD psql -h localhost -U postgres -d stokr_lite -c \"SELECT symbol, timestamp, close FROM candle_data WHERE timeframe='daily' AND timestamp >= '2026-07-13' LIMIT 10;\""))

# Check all dates
print("\n=== All daily candle dates ===")
print(remote("PGPASSWORD=`$POSTGRES_PASSWORD psql -h localhost -U postgres -d stokr_lite -c \"SELECT timestamp::date as day, count(*) FROM candle_data WHERE timeframe='daily' AND timestamp >= '2026-07-01' GROUP BY day ORDER BY day;\""))

# Run EMA50D backtest with verbose output - check raw response
print("\n=== EMA50D raw backtest response ===")
result = remote("""curl -s -X POST 'http://localhost:8081/api/backtest/advanced' -H 'Content-Type: application/x-www-form-urlencoded' -d 'strategy=EMA50_DISTANCE&symbolGroup=NIFTY_100&dateStart=2026-07-10&dateEnd=2026-07-13&capital=25000'""")
try:
    data = json.loads(result)
    trades = data.get('trades', [])
    print(f"Total trades: {len(trades)}")
    # Show all trades from Jul 13
    for t in trades:
        entry_time = t.get('entryTime', '')
        if '2026-07-13' in str(entry_time) or '2026-07-11' in str(entry_time) or '2026-07-10' in str(entry_time):
            print(f"  {t.get('symbol','')} | {t.get('side','')} | Entry: {t.get('entryPrice','')} | {entry_time}")
    # Show last 5 trades
    print(f"\nLast 5 trades:")
    for t in trades[-5:]:
        print(f"  {t.get('symbol','')} | {t.get('side','')} | Entry: {t.get('entryPrice','')} | {t.get('entryTime','')}")
except Exception as e:
    print(f"Error: {e}")
    print(result[:500])

