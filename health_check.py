import subprocess

def db(sql):
    r = subprocess.run([
        "ssh", "-o", "ConnectTimeout=30", "-o", "StrictHostKeyChecking=no",
        "root@173.249.55.84",
        f'PGPASSWORD=`$POSTGRES_PASSWORD psql -h localhost -U postgres -d stokr_lite -t -A -c "{sql}"'
    ], capture_output=True, text=True, timeout=30)
    return r.stdout.strip()

print("=" * 60)
print("COMPREHENSIVE APPLICATION HEALTH CHECK")
print("=" * 60)

# 1. Docker containers
print("\n[1] DOCKER CONTAINERS")
r = subprocess.run(["ssh", "-o", "ConnectTimeout=30", "-o", "StrictHostKeyChecking=no",
    "root@173.249.55.84",
    "docker ps --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'"],
    capture_output=True, text=True, timeout=30)
print(r.stdout)

# 2. Backend API
print("\n[2] BACKEND API HEALTH")
r = subprocess.run(["ssh", "-o", "ConnectTimeout=30", "-o", "StrictHostKeyChecking=no",
    "root@173.249.55.84",
    "curl -s -o /dev/null -w '%{http_code}' http://localhost:8081/api/strategies"],
    capture_output=True, text=True, timeout=15)
print(f"  /api/strategies: HTTP {r.stdout}")

r = subprocess.run(["ssh", "-o", "ConnectTimeout=30", "-o", "StrictHostKeyChecking=no",
    "root@173.249.55.84",
    "curl -s -o /dev/null -w '%{http_code}' http://localhost:8081/api/deployments"],
    capture_output=True, text=True, timeout=15)
print(f"  /api/deployments: HTTP {r.stdout}")

r = subprocess.run(["ssh", "-o", "ConnectTimeout=30", "-o", "StrictHostKeyChecking=no",
    "root@173.249.55.84",
    "curl -s -o /dev/null -w '%{http_code}' http://localhost:8081/api/orders"],
    capture_output=True, text=True, timeout=15)
print(f"  /api/orders: HTTP {r.stdout}")

r = subprocess.run(["ssh", "-o", "ConnectTimeout=30", "-o", "StrictHostKeyChecking=no",
    "root@173.249.55.84",
    "curl -s -o /dev/null -w '%{http_code}' http://localhost:8081/api/signals"],
    capture_output=True, text=True, timeout=15)
print(f"  /api/signals: HTTP {r.stdout}")

# 3. Nginx
print("\n[3] NGINX / FRONTEND")
r = subprocess.run(["ssh", "-o", "ConnectTimeout=30", "-o", "StrictHostKeyChecking=no",
    "root@173.249.55.84",
    "curl -s -o /dev/null -w '%{http_code}' http://localhost"],
    capture_output=True, text=True, timeout=15)
print(f"  localhost: HTTP {r.stdout}")

# 4. DB row counts
print("\n[4] DATABASE ROW COUNTS")
tables = ['strategies', 'deployments', 'strategy_signals', 'orders', 'positions',
          'candle_data', 'broker_accounts', 'backtest_results', 'universe_groups', 'universe_symbols']
for t in tables:
    try:
        count = db(f"SELECT COUNT(*) FROM {t};")
        print(f"  {t}: {count}")
    except:
        print(f"  {t}: TABLE MISSING")

# 5. Candle data
print("\n[5] CANDLE DATA RANGES")
rows = db("SELECT timeframe, COUNT(*), MIN(timestamp)::date, MAX(timestamp)::date FROM candle_data GROUP BY timeframe ORDER BY timeframe;")
for line in rows.split('\n'):
    if line.strip():
        parts = line.split('|')
        print(f"  {parts[0].strip()}: {parts[1].strip()} candles, {parts[2].strip()} to {parts[3].strip()}")

# 6. Strategies
print("\n[6] STRATEGIES")
rows = db("SELECT id, name, timeframe, enabled FROM strategies ORDER BY id;")
for line in rows.split('\n'):
    if line.strip():
        print(f"  {line}")

# 7. Deployments
print("\n[7] DEPLOYMENTS")
rows = db("""SELECT d.id, s.name, d.status, d.capital, d.broker_account_id, d.mode
FROM deployments d JOIN strategies s ON d.strategy_id=s.id ORDER BY d.id;""")
for line in rows.split('\n'):
    if line.strip():
        print(f"  {line}")

# 8. Recent signals
print("\n[8] RECENT SIGNALS (last 10)")
rows = db("""SELECT id, symbol, signal_type, status, deployment_id, created_at::text
FROM strategy_signals ORDER BY created_at DESC LIMIT 10;""")
for line in rows.split('\n'):
    if line.strip():
        print(f"  {line}")

# 9. Signal status distribution
print("\n[9] SIGNAL STATUS DISTRIBUTION")
rows = db("SELECT status, COUNT(*) FROM strategy_signals GROUP BY status ORDER BY COUNT(*) DESC;")
for line in rows.split('\n'):
    if line.strip():
        print(f"  {line}")

# 10. Open positions
print("\n[10] OPEN POSITIONS")
rows = db("SELECT id, symbol, side, quantity, entry_price, unrealized_pnl FROM positions WHERE status='OPEN' ORDER BY symbol;")
for line in rows.split('\n'):
    if line.strip():
        print(f"  {line}")
if not rows.strip():
    print("  (none)")

# 11. Recent orders
print("\n[11] RECENT ORDERS (last 10)")
rows = db("""SELECT id, symbol, side, order_type, status, quantity, price, created_at::text
FROM orders ORDER BY created_at DESC LIMIT 10;""")
for line in rows.split('\n'):
    if line.strip():
        print(f"  {line}")

# 12. Broker accounts
print("\n[12] BROKER ACCOUNTS")
rows = db("SELECT id, broker_name, client_id, status, auto_reconnect, token_expiry::text FROM broker_accounts;")
for line in rows.split('\n'):
    if line.strip():
        print(f"  {line}")

# 13. Integrity checks
print("\n[13] INTEGRITY CHECKS")
checks = [
    ("SELECT COUNT(*) FROM deployments WHERE broker_account_id IS NULL;",
     "Deployments with NULL broker_account_id"),
    ("SELECT COUNT(*) FROM strategy_signals WHERE deployment_id IS NULL;",
     "Signals with NULL deployment_id"),
    ("SELECT COUNT(*) FROM strategy_signals s WHERE s.status='EXECUTED' AND NOT EXISTS (SELECT 1 FROM orders o WHERE o.signal_id=s.id);",
     "EXECUTED signals with no order"),
    ("SELECT COUNT(*) FROM positions WHERE status='OPEN' AND deployment_id IS NULL;",
     "Open positions with NULL deployment_id"),
    ("SELECT COUNT(*) FROM positions WHERE status='OPEN' AND symbol NOT IN (SELECT symbol FROM strategy_signals WHERE status='EXECUTED');",
     "Open positions with no matching EXECUTED signal"),
]
for sql, label in checks:
    try:
        count = db(sql)
        status = "WARN" if int(count) > 0 else "OK"
        print(f"  [{status}] {label}: {count}")
    except Exception as e:
        print(f"  [ERROR] {label}: {e}")

# 14. Backend logs - errors
print("\n[14] BACKEND ERRORS (last 50 lines)")
r = subprocess.run(["ssh", "-o", "ConnectTimeout=30", "-o", "StrictHostKeyChecking=no",
    "root@173.249.55.84",
    "docker logs stokr-lite-backend --tail 50 2>&1 | grep -iE 'ERROR|Exception|fail|WARN' | tail -15"],
    capture_output=True, text=True, timeout=30)
print(r.stdout if r.stdout.strip() else "  No errors found")

# 15. Execution engine activity
print("\n[15] EXECUTION ENGINE (last 20 backend log lines)")
r = subprocess.run(["ssh", "-o", "ConnectTimeout=30", "-o", "StrictHostKeyChecking=no",
    "root@173.249.55.84",
    "docker logs stokr-lite-backend --tail 20 2>&1 | grep -iE 'process|scan|signal|entry|exit|reconcil|scheduler' | tail -10"],
    capture_output=True, text=True, timeout=30)
print(r.stdout if r.stdout.strip() else "  (no activity)")

# 16. Crontab
print("\n[16] CRONTAB")
r = subprocess.run(["ssh", "-o", "ConnectTimeout=30", "-o", "StrictHostKeyChecking=no",
    "root@173.249.55.84", "crontab -l 2>&1"],
    capture_output=True, text=True, timeout=15)
print(r.stdout)

# 17. Disk space
print("\n[17] DISK SPACE")
r = subprocess.run(["ssh", "-o", "ConnectTimeout=30", "-o", "StrictHostKeyChecking=no",
    "root@173.249.55.84", "df -h / | tail -1"],
    capture_output=True, text=True, timeout=15)
print(f"  {r.stdout.strip()}")

# 18. Frontend file permissions
print("\n[18] FRONTEND FILE PERMISSIONS")
r = subprocess.run(["ssh", "-o", "ConnectTimeout=30", "-o", "StrictHostKeyChecking=no",
    "root@173.249.55.84", "ls -la /opt/stokr/ui/ | head -15"],
    capture_output=True, text=True, timeout=15)
print(r.stdout)

# 19. DB size
print("\n[19] DATABASE SIZE")
rows = db("SELECT pg_size_pretty(pg_database_size('stokr_lite'));")
print(f"  stokr_lite: {rows}")

# 20. Strategy-universe mappings
print("\n[20] STRATEGY-UNIVERSE MAPPINGS")
try:
    rows = db("""SELECT s.name, ug.group_key, COUNT(us.symbol) as symbols
    FROM strategy_universe_mappings sum
    JOIN strategies s ON sum.strategy_id=s.id
    JOIN universe_groups ug ON sum.group_id=ug.id
    LEFT JOIN universe_symbols us ON us.group_id=ug.id
    GROUP BY s.name, ug.group_key;""")
    for line in rows.split('\n'):
        if line.strip():
            print(f"  {line}")
except:
    print("  (table not found or no mappings)")

print("\n" + "=" * 60)
print("CHECK COMPLETE")
print("=" * 60)

