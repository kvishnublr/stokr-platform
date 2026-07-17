import psycopg2
conn = psycopg2.connect('host=localhost dbname=stokr_lite user=postgres password=stokr2026')
cur = conn.cursor()
cur.execute("SELECT table_name FROM information_schema.tables WHERE table_schema='public' AND table_name LIKE '%candle%' OR table_name LIKE '%daily%' OR table_name LIKE '%bar%' ORDER BY table_name")
for r in cur.fetchall():
    print(r[0])
conn.close()
