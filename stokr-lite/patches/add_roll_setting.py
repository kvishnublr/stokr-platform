import subprocess
p = subprocess.run(
    ['psql', '-h', 'localhost', '-U', 'postgres', '-d', 'stokr_lite', '-t', '-A', '-c',
     "SELECT setting_key, setting_value FROM option_arb_auto_exec_settings ORDER BY setting_key;"],
    capture_output=True, text=True, env={'PGPASSWORD': '`$POSTGRES_PASSWORD'}
)
print(p.stdout)

# Insert roll_threshold_pct if not exists
p2 = subprocess.run(
    ['psql', '-h', 'localhost', '-U', 'postgres', '-d', 'stokr_lite', '-c',
     "INSERT INTO option_arb_auto_exec_settings (setting_key, setting_value) VALUES ('roll_threshold_pct', '5.0') ON CONFLICT DO NOTHING;"],
    capture_output=True, text=True, env={'PGPASSWORD': '`$POSTGRES_PASSWORD'}
)
print(p2.stdout or "Inserted roll_threshold_pct")

