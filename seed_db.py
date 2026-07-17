import psycopg2
conn = psycopg2.connect("dbname=stokr_lite user=postgres host=/var/run/postgresql")
cur = conn.cursor()

# Run V36 insert
cur.execute("""
INSERT INTO strategies (name, description, strategy_type, asset_class, params_schema, enabled, created_at, updated_at)
VALUES ('NIFTY Weekly Calendar Spread', 'Sell current-week ATM CE, buy next-week ATM CE.', 'NIFTY_CALENDAR_SPREAD', 'NFO',
 '{"max_positions":1,"lots":1,"capital_per_trade":1750}', false, NOW(), NOW())
ON CONFLICT (name) DO NOTHING;
""")

# Run V37 insert
cur.execute("""
INSERT INTO strategies (name, description, strategy_type, asset_class, params_schema, enabled, created_at, updated_at)
VALUES ('Insider Momentum', 'Promoter buys + trend confirmation. 4x5K positions. Target 15%.', 'INSIDER_MOMENTUM', 'EQUITY',
 '{"max_positions":4,"capital_per_trade":5000,"max_hold_days":21}', true, NOW(), NOW())
ON CONFLICT (name) DO NOTHING;
""")

conn.commit()

# Record in flyway history
cur.execute("INSERT INTO flyway_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (36, '36', 'nifty calendar spread', 'SQL', 'V36__seed_nifty_calendar_spread.sql', 0, 'postgres', NOW(), 0, true) ON CONFLICT (version) DO NOTHING")
cur.execute("INSERT INTO flyway_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (37, '37', 'insider momentum', 'SQL', 'V37__seed_insider_momentum.sql', 0, 'postgres', NOW(), 0, true) ON CONFLICT (version) DO NOTHING")
conn.commit()

# Verify
cur.execute("SELECT name, strategy_type, enabled FROM strategies ORDER BY id DESC LIMIT 5")
for r in cur.fetchall():
    print(f"  {r[1]}: {r[0]} (enabled={r[2]})")

conn.close()
print("Done")
