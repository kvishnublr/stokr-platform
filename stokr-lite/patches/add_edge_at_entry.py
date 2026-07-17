import subprocess
p = subprocess.run(
    ['psql', '-h', 'localhost', '-U', 'postgres', '-d', 'stokr_lite', '-c',
     "ALTER TABLE option_arb_executed_trades ADD COLUMN IF NOT EXISTS edge_at_entry DOUBLE PRECISION;"],
    capture_output=True, text=True, env={'PGPASSWORD': 'stokr2026'}
)
print(p.stdout or p.stderr)
