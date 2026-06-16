"""
ORB paper-trading tracker — run anytime to see live-vs-backtest.

Reports the REAL (paper) ORB signals the platform generates during market hours
(backtest_run_id IS NULL), their outcomes and realized P&L, per day, and compares
to the backtest baseline (+4.14%/month, 33% win, ~12 trades/day, ~₹3,100/month).

Usage: ssh root@173.249.55.84 "python3 /tmp/orb_paper_tracker.py"
"""
import psycopg2, psycopg2.extras
from collections import defaultdict
from decimal import Decimal

DB_PW = "root123"
conn = psycopg2.connect(host="localhost", port=5432, dbname="stokr_platform", user="postgres", password=DB_PW)
cur = conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor)

cur.execute("""
    SELECT candle_timestamp, symbol, signal_type, outcome_status,
           realized_pnl, entry_reference_price, exit_price, expiry_reason, created_at
    FROM strategy_signals
    WHERE strategy_name = 'OPENING_RANGE_BREAKOUT'
      AND backtest_run_id IS NULL
    ORDER BY created_at
""")
rows = cur.fetchall()

print("=" * 70)
print("ORB PAPER-TRADING TRACKER   (live-vs-backtest)")
print("Backtest baseline: +4.14%/mo (₹3,108), 33% win, max DD ₹942, ~12 trades/day")
print("=" * 70)

if not rows:
    print("\nNo ORB paper trades yet.")
    print("ORB scans 09:30-14:30 IST; signals appear after the next market session.")
    print("(Strategy is wired in PAPER mode with a ₹1,000 daily loss limit.)")
    cur.close(); conn.close()
    raise SystemExit

daily = defaultdict(lambda: {"n": 0, "pnl": Decimal(0), "open": 0, "win": 0, "loss": 0})
total = Decimal(0); wins = 0; closed = 0; openpos = 0
for r in rows:
    d = r["created_at"].date()
    daily[d]["n"] += 1
    status = (r["outcome_status"] or "RUNNING").upper()
    pnl = Decimal(str(r["realized_pnl"])) if r["realized_pnl"] is not None else None
    if status in ("RUNNING", "PENDING", "OPEN") or pnl is None:
        daily[d]["open"] += 1; openpos += 1
    else:
        daily[d]["pnl"] += pnl; total += pnl; closed += 1
        if pnl > 0: daily[d]["win"] += 1; wins += 1
        else: daily[d]["loss"] += 1

print(f"\n{'Date':<12}{'Signals':>8}{'Closed':>8}{'Open':>6}{'Day P&L':>10}{'Win%':>7}")
print("-" * 51)
for d in sorted(daily):
    x = daily[d]
    c = x["win"] + x["loss"]
    wr = x["win"] / c * 100 if c else 0
    print(f"{str(d):<12}{x['n']:>8}{c:>8}{x['open']:>6} ₹{x['pnl']:>+8,.0f}{wr:>6.0f}%")

print("-" * 51)
print(f"Total signals: {len(rows)}   Closed: {closed}   Still open: {openpos}")
if closed:
    print(f"Overall win rate: {wins/closed*100:.1f}%   (backtest: 33%)")
    print(f"Realized P&L so far: ₹{total:+,.0f}   ({total/Decimal('75000')*100:+.2f}% on ₹75k)")
print("\nVERDICT:")
if closed >= 20:
    pct = total/Decimal('75000')*100
    if pct >= 2:
        print("  Tracking AT or ABOVE backtest — strong. Consider BOTH (live) after 2 wks.")
    elif pct >= 0:
        print("  Positive but below backtest pace. Keep on paper, gather more data.")
    else:
        print("  Underperforming backtest. Do NOT go live; investigate divergence.")
else:
    print(f"  Only {closed} closed trades — need ~20+ for a read. Keep collecting.")
cur.close(); conn.close()
