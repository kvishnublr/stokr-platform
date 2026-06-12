import paramiko, json

c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)

def q(sql):
    s = " ".join(sql.split())
    _, o, e = c.exec_command(f'docker exec stokr-postgres psql -U postgres -d stokr_platform -c "{s}"')
    return (o.read()+e.read()).decode()

print("=== IST ===")
_, o, _ = c.exec_command("TZ=Asia/Kolkata date")
print(o.read().decode())

print("\n=== Signals today (strategy_signals) ===")
print(q("""
SELECT strategy_name, count(*), max(created_at)
FROM strategy_signals WHERE created_at >= current_date AND deleted = false
GROUP BY strategy_name ORDER BY count(*) DESC;
"""))

print("\n=== Signal pipeline status today ===")
print(q("""
SELECT column_name FROM information_schema.columns
WHERE table_name='signal_execution_tracks' ORDER BY ordinal_position;
"""))
print(q("""
SELECT strategy_name, lifecycle_status, count(*)
FROM signal_execution_tracks
WHERE created_at >= current_date
GROUP BY strategy_name, lifecycle_status
ORDER BY count(*) DESC LIMIT 20;
"""))

print("\n=== Runtime health today ===")
print(q("""
SELECT strategy_name, execution_mode, scans_attempted, scans_blocked_integrity,
       signals_generated, trades_opened, trades_closed, last_signal_time, last_rejection_reason
FROM strategy_runtime_health
WHERE session_date = current_date
ORDER BY scans_attempted DESC;
"""))

print("\n=== Bindings count ===")
print(q("SELECT count(*) FROM strategy_runtime_bindings WHERE deleted = false;"))

print("\n=== Execution configs ===")
print(q("""
SELECT s.name, sec.execution_mode, sec.active
FROM strategy_execution_configs sec
JOIN strategies s ON s.id = sec.strategy_id
ORDER BY s.name;
"""))

print("\n=== Orders today ===")
print(q("""
SELECT count(*), max(created_at) FROM oms_orders WHERE created_at >= current_date;
"""))

print("\n=== Open/stuck tracks (7d) ===")
print(q("""
SELECT strategy_name, lifecycle_status, count(*)
FROM signal_execution_tracks
WHERE created_at >= current_date - interval '7 days'
  AND lifecycle_status IN ('AWAITING_CLOSE','UNRECONCILED','OPEN','ACTIVE')
GROUP BY strategy_name, lifecycle_status
ORDER BY count(*) DESC LIMIT 15;
"""))

print("\n=== CDS candles freshness ===")
print(q("""
SELECT symbol, max(candle_timestamp) last_candle
FROM market_data_candles
WHERE symbol IN ('USDINR','EURINR') OR symbol LIKE '%USDINR%' OR symbol LIKE '%EURINR%'
GROUP BY symbol ORDER BY symbol LIMIT 10;
"""))

print("\n=== Catalog scan logs (30m) ===")
_, o, e = c.exec_command(
    "docker logs stokr-api --since 30m 2>&1 | grep -E 'catalog.scan|integrity.block|signal.persist|UNRECONCILED|ERROR' | tail -30"
)
print((o.read()+e.read()).decode() or "(none)")

c.close()
