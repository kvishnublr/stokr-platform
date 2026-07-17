import subprocess

queries = [
    ("SCHEMA", "\d strategy_signals"),
    ("TODAY ALL", "SELECT * FROM strategy_signals WHERE created_at >= '2026-07-13' ORDER BY created_at DESC LIMIT 10;"),
    ("LAST 10 ALL TIME", "SELECT id, symbol, strategy_id, created_at FROM strategy_signals ORDER BY created_at DESC LIMIT 10;"),
]

for label, q in queries:
    r = subprocess.run([
        "ssh", "-o", "ConnectTimeout=30", "-o", "StrictHostKeyChecking=no",
        "root@173.249.55.84",
        "PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -c " + repr(q)
    ], capture_output=True, text=True, timeout=20)
    print(f"=== {label} ===")
    print(r.stdout if r.stdout else r.stderr)
    print()
