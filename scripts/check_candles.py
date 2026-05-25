#!/usr/bin/env python3
import subprocess
import sys

sql = """
SELECT symbol, timeframe, count(*) AS n,
       min(open_time) AS mn, max(open_time) AS mx
FROM marketdata_candles
WHERE deleted = false
GROUP BY symbol, timeframe
ORDER BY n DESC
LIMIT 15;
"""
cmd = [
    "docker", "exec", "stokr-postgres",
    "psql", "-U", "postgres", "-d", "stokr_platform",
    "-c", sql,
]
r = subprocess.run(cmd, capture_output=True, text=True)
print(r.stdout)
if r.returncode != 0:
    print(r.stderr, file=sys.stderr)
    sys.exit(r.returncode)
