import psycopg2
conn = psycopg2.connect("dbname=stokr_lite user=postgres host=/var/run/postgresql")
cur = conn.cursor()

# Check new strategies
cur.execute("SELECT id, name, strategy_type, enabled FROM strategies ORDER BY id DESC LIMIT 8")
print("=== All Strategies ===")
for r in cur.fetchall():
    print(f"  ID={r[0]} {r[2]}: {r[1]} (enabled={r[3]})")

# Count total
cur.execute("SELECT count(*) FROM strategies")
print(f"\nTotal: {cur.fetchone()[0]} strategies")

# Check if Insider Momentum and Calendar Spread exist
cur.execute("SELECT count(*) FROM strategies WHERE strategy_type IN ('INSIDER_MOMENTUM', 'NIFTY_CALENDAR_SPREAD')")
c = cur.fetchone()[0]
print(f"New strategies found: {c}")

conn.close()
