"""
ADV_CASH live-paper accuracy tracker (the flat-day / order-flow strategy).
Measures the REAL paper signals generated from the live order book — the thing
the backtest cannot see. Promote to live only if accuracy holds over 1-2 weeks.

Usage: ssh root@173.249.55.84 "python3 /tmp/advcash_tracker.py"
"""
import psycopg2, psycopg2.extras
from collections import defaultdict
from decimal import Decimal

conn = psycopg2.connect(host="localhost", port=5432, dbname="stokr_platform", user="postgres", password="root123")
cur = conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor)

cur.execute("""
    SELECT created_at::date AS d, realized_pnl
    FROM strategy_signals
    WHERE strategy_name='ADV_CASH' AND backtest_run_id IS NULL AND realized_pnl IS NOT NULL
    ORDER BY created_at
""")
rows = cur.fetchall()

print("=" * 64)
print("ADV_CASH LIVE-PAPER TRACKER  (flat-day order-flow strategy)")
print("Promote to LIVE only if win rate stays >= ~40% over 8-10+ trading days")
print("=" * 64)
if not rows:
    print("\nNo ADV_CASH paper trades yet today. It scans 09:15-14:30 (skips 11:00-13:15).")
    cur.close(); conn.close(); raise SystemExit

daily = defaultdict(lambda: {"n": 0, "w": 0, "pnl": Decimal(0)})
tot = Decimal(0); w = 0; n = 0
for r in rows:
    p = Decimal(str(r["realized_pnl"]))
    x = daily[r["d"]]; x["n"] += 1; x["pnl"] += p; n += 1; tot += p
    if p > 0: x["w"] += 1; w += 1

print(f"\n{'Date':<12}{'Trades':>8}{'Wins':>7}{'Win%':>7}{'P&L':>10}")
print("-" * 44)
for d in sorted(daily):
    x = daily[d]
    print(f"{str(d):<12}{x['n']:>8}{x['w']:>7}{x['w']/x['n']*100:>6.0f}%{float(x['pnl']):>+10,.0f}")
print("-" * 44)
print(f"TOTAL: {n} trades | {w} wins | win rate {w/n*100:.1f}% | P&L Rs{float(tot):+,.0f}")
print(f"(P&L is at the size traded — currently 1 share; scales ~2-3x at Rs5k/trade)")
days = len(daily)
print(f"\nTrading days of data: {days}")
if days >= 8 and w/n*100 >= 40:
    print("VERDICT: Win rate holding >=40% over enough days -> candidate to promote LIVE.")
elif days >= 8:
    print(f"VERDICT: Win rate {w/n*100:.0f}% below 40% over {days} days -> keep PAPER.")
else:
    print(f"VERDICT: Only {days} days of data -> need 8-10+ before deciding. Keep PAPER.")
cur.close(); conn.close()
