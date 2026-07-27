#!/usr/bin/env python3
"""Check today's signal counts"""
import subprocess
r = subprocess.run(
    ['ssh', 'root@173.249.55.84',
     'PGPASSWORD=`$POSTGRES_PASSWORD psql -h localhost -U postgres -d stokr_lite -c "SELECT symbol, status, COUNT(*) as cnt FROM strategy_signals WHERE created_at >= \'2026-07-14\' GROUP BY symbol, status ORDER BY cnt DESC LIMIT 30;"'],
    capture_output=True, text=True, timeout=30)
print(r.stdout)
print(r.stderr)

