import subprocess

queries = [
    ("TODAY SIGNALS", "SELECT id, symbol, strategy_id, signal_type, confidence, entry_price, created_at FROM strategy_signals WHERE created_at >= '2026-07-13' ORDER BY created_at DESC LIMIT 20;"),
    ("TOTAL TODAY", "SELECT count(*) as total FROM strategy_signals WHERE created_at >= '2026-07-13';"),
    ("YESTERDAY", "SELECT count(*) as total FROM strategy_signals WHERE created_at >= '2026-07-12' AND created_at < '2026-07-13';"),
]

for label, q in queries:
    r = subprocess.run([
        "ssh", "-o", "ConnectTimeout=30", "-o", "StrictHostKeyChecking=no",
        "root@173.249.55.84",
        "PGPASSWORD=`$POSTGRES_PASSWORD psql -h localhost -U postgres -d stokr_lite -c " + repr(q)
    ], capture_output=True, text=True, timeout=20)
    print(f"=== {label} ===")
    print(r.stdout if r.stdout else r.stderr)
    print()

