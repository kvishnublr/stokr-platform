"""
Daily P&L distribution + max single-day drawdown + intraday DD within worst day.
Realistic portfolio: 15 max concurrent, 1 per symbol, fixed exits, 8 bps cost.
"""
import psycopg2, psycopg2.extras
from collections import defaultdict
from decimal import Decimal

DB_PW = "root123"
conn = psycopg2.connect(host="localhost", port=5432, dbname="stokr_platform", user="postgres", password=DB_PW)
cur = conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor)
CAP = Decimal("5000"); RS, RE = "2026-05-19 04:00:00+00", "2026-06-12 10:00:00+00"
CUT = "2026-06-14 07:05:00+00"
COST_BPS = Decimal("8")
RT_COST = CAP * COST_BPS / Decimal("10000")
STRATS = ["ADV_CASH", "VWAP_SQUEEZE", "VWAP_CLOSE_RECLAIM", "NIFTY_CATCHUP", "VWAP_BOUNCE"]

_cc = {}
def candles(sym):
    if sym in _cc: return _cc[sym]
    cur.execute("SELECT open_time,high_price,low_price,close_price FROM marketdata_candles WHERE symbol=%s AND timeframe='1m' AND open_time>=%s AND open_time<=%s ORDER BY open_time ASC", (sym, RS, RE))
    rows = cur.fetchall()
    lst = [(r["open_time"], Decimal(str(r["high_price"])), Decimal(str(r["low_price"])), Decimal(str(r["close_price"]))) for r in rows]
    _cc[sym] = (lst, {ot: i for i, (ot, *_) in enumerate(lst)})
    return _cc[sym]

trades = []
for strat in STRATS:
    cur.execute("""SELECT DISTINCT ON (symbol) id,symbol FROM backtest_runs WHERE strategy_key=%s AND status='COMPLETED' AND range_start=%s AND range_end=%s AND created_at>=%s ORDER BY symbol,created_at DESC""", (strat, RS, RE, CUT))
    runs = cur.fetchall()
    if not runs: continue
    ph = ",".join(["%s"] * len(runs))
    cur.execute(f"""SELECT symbol,candle_timestamp,entry_reference_price,target_price,stop_price FROM strategy_signals WHERE backtest_run_id::text IN ({ph}) AND entry_reference_price>0 AND target_price IS NOT NULL AND stop_price IS NOT NULL ORDER BY candle_timestamp""", [str(r["id"]) for r in runs])
    for s in cur.fetchall():
        sym = s["symbol"]; entry = Decimal(str(s["entry_reference_price"])); tgt = Decimal(str(s["target_price"])); stp = Decimal(str(s["stop_price"]))
        qty = int(CAP / entry)
        if qty == 0: continue
        is_long = tgt >= entry
        lst, idx = candles(sym); start = idx.get(s["candle_timestamp"])
        if start is None: continue
        xp = None; xt = None
        for i in range(start + 1, len(lst)):
            ot, h, l, c = lst[i]
            if is_long:
                if l <= stp: xp, xt = stp, ot; break
                if h >= tgt: xp, xt = tgt, ot; break
            else:
                if h >= stp: xp, xt = stp, ot; break
                if l <= tgt: xp, xt = tgt, ot; break
            if ot.hour == 10 and ot.minute == 0: xp, xt = c, ot; break
        if xp is None: continue
        gross = (xp - entry) * qty if is_long else (entry - xp) * qty
        trades.append((s["candle_timestamp"], xt, gross - RT_COST, strat, sym))

# 15-pos / 1-symbol cap, then by-day P&L + by-trade equity curve
trades.sort(key=lambda t: t[0])
open_sym = {}
daily_pnl = defaultdict(lambda: Decimal(0))
daily_n = defaultdict(int)
equity = Decimal(0); peak = Decimal(0); maxdd = Decimal(0); maxdd_day = None
intraday_peak = defaultdict(lambda: Decimal(0))   # per-day running peak
intraday_dd = defaultdict(lambda: Decimal(0))     # per-day max intraday DD
running = defaultdict(lambda: Decimal(0))         # per-day running equity
for et, xt, p, strat, sym in trades:
    for s in [s for s, x in open_sym.items() if x <= et]: del open_sym[s]
    if sym in open_sym or len(open_sym) >= 15: continue
    open_sym[sym] = xt
    day = et.date()
    daily_pnl[day] += p; daily_n[day] += 1
    # cumulative equity for overall max DD
    equity += p
    if equity > peak: peak = equity
    dd = equity - peak
    if dd < maxdd:
        maxdd = dd; maxdd_day = day
    # intraday DD tracking
    running[day] += p
    if running[day] > intraday_peak[day]: intraday_peak[day] = running[day]
    iddpct = running[day] - intraday_peak[day]
    if iddpct < intraday_dd[day]: intraday_dd[day] = iddpct

days = sorted(daily_pnl.keys())
total = sum(daily_pnl.values())
print("=" * 64)
print("DAILY P&L DISTRIBUTION & DRAWDOWN")
print(f"Capital ₹75k, ₹5k/trade, 15 max conc, 1/symbol, {COST_BPS}bps cost")
print(f"Strategies: ADV_CASH, VWAP_SQUEEZE, VWAP_CLOSE_RECLAIM, NIFTY_CATCHUP, VWAP_BOUNCE")
print("=" * 64)
print(f"\n{'Date':<12} {'Trades':>7} {'Day P&L':>10} {'Intraday DD':>14} {'Equity':>10}")
print("-" * 56)
eq = Decimal(0)
for d in days:
    eq += daily_pnl[d]
    print(f"{str(d):<12} {daily_n[d]:>7} ₹{daily_pnl[d]:>+8,.0f}  ₹{intraday_dd[d]:>+10,.0f}  ₹{eq:>+8,.0f}")

# Stats
sorted_pnl = sorted(daily_pnl.values())
n_days = len(sorted_pnl)
n_pos = sum(1 for p in sorted_pnl if p > 0)
n_neg = sum(1 for p in sorted_pnl if p < 0)
worst = min(sorted_pnl); best = max(sorted_pnl)
worst_day = min(daily_pnl, key=daily_pnl.get)
worst_intraday = min(intraday_dd.values())
worst_intraday_day = min(intraday_dd, key=intraday_dd.get)
print("\n" + "=" * 64)
print(f"Total trading days: {n_days}")
print(f"Positive days: {n_pos} ({n_pos/n_days*100:.0f}%)   Negative days: {n_neg} ({n_neg/n_days*100:.0f}%)")
print(f"Average day P&L: ₹{total/n_days:.0f}")
print(f"Best day:  ₹{best:+.0f}  ({max(daily_pnl, key=daily_pnl.get)})")
print(f"WORST DAY (closing P&L):     ₹{worst:+.0f}  ({worst_day})")
print(f"WORST INTRADAY DRAWDOWN: ₹{worst_intraday:+.0f}  ({worst_intraday_day})")
print(f"OVERALL EQUITY MAX DRAWDOWN: ₹{maxdd:+.0f}  (low reached around {maxdd_day})")
print(f"Month total: ₹{total:+.0f}")
cur.close(); conn.close()
