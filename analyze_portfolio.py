"""
Portfolio backtest with realistic constraints:
  - ₹5,000 per trade
  - 15 MAX concurrent open positions (₹75k deployed cap)
  - A signal is SKIPPED if 15 positions are already open at its entry time.

For each signal we scan forward 1m bars to find the exit (TP / SL / end-of-day),
recording entry_time, exit_time and P&L. Then we replay all signals across ALL
strategies+symbols in chronological order, honouring the 15-slot cap.

Usage: python3 analyze_portfolio.py <cutoff> <range: WK|MO>
"""
import psycopg2, psycopg2.extras, sys
from collections import defaultdict
from decimal import Decimal

DB_PW = "33Alu8vwlQpQPMuukjEj9SLrUx14D6PEWSIxga47jSI="
conn = psycopg2.connect(host="localhost", port=5432, dbname="stokr_platform",
                        user="postgres", password=DB_PW)
cur = conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor)

TRADE_CAPITAL = Decimal("5000")
MAX_POS = 15
CUTOFF = sys.argv[1] if len(sys.argv) > 1 else "2026-06-14 07:05:00+00"
WHICH = sys.argv[2] if len(sys.argv) > 2 else "MO"
# Round-trip transaction cost in basis points of the position (fee both sides +
# slippage both sides). 0 = gross. ~6-10 bps is realistic for liquid NSE cash.
COST_BPS = Decimal(sys.argv[3]) if len(sys.argv) > 3 else Decimal("0")
ROUND_TRIP_COST = TRADE_CAPITAL * COST_BPS / Decimal("10000")

if WHICH == "WK":
    RANGE_START, RANGE_END, LABEL = "2026-06-06 04:00:00+00", "2026-06-12 10:00:00+00", "1-WEEK"
else:
    RANGE_START, RANGE_END, LABEL = "2026-05-19 04:00:00+00", "2026-06-12 10:00:00+00", "1-MONTH"

STRATEGIES = ["ADV_CASH", "NIFTY_CATCHUP", "VWAP_BOUNCE", "VWAP_SQUEEZE", "VWAP_CLOSE_RECLAIM"]

# candle cache per symbol
_candle_cache = {}
def candles(symbol):
    if symbol in _candle_cache:
        return _candle_cache[symbol]
    cur.execute("""SELECT open_time, high_price, low_price, close_price
                   FROM marketdata_candles WHERE symbol=%s AND timeframe='1m'
                   AND open_time >= %s AND open_time <= %s ORDER BY open_time ASC""",
                (symbol, RANGE_START, RANGE_END))
    rows = cur.fetchall()
    lst = [(r["open_time"], Decimal(str(r["high_price"])), Decimal(str(r["low_price"])),
            Decimal(str(r["close_price"]))) for r in rows]
    idx = {ot: i for i, (ot, *_ ) in enumerate(lst)}
    _candle_cache[symbol] = (lst, idx)
    return _candle_cache[symbol]

all_trades = []          # (entry_time, exit_time, pnl, strategy, won)
per_strat = defaultdict(lambda: {"n":0,"pnl":Decimal(0),"win":0,"loss":0})

for strat in STRATEGIES:
    cur.execute("""SELECT DISTINCT ON (symbol) id, symbol FROM backtest_runs
                   WHERE strategy_key=%s AND status='COMPLETED'
                   AND range_start=%s AND range_end=%s AND created_at>=%s
                   ORDER BY symbol, created_at DESC""",
                (strat, RANGE_START, RANGE_END, CUTOFF))
    runs = cur.fetchall()
    if not runs:
        continue
    run_ids = [str(r["id"]) for r in runs]
    ph = ",".join(["%s"]*len(run_ids))
    cur.execute(f"""SELECT symbol, signal_type, candle_timestamp, entry_reference_price,
                    target_price, stop_price FROM strategy_signals
                    WHERE backtest_run_id::text IN ({ph})
                    AND entry_reference_price>0 AND target_price IS NOT NULL
                    AND stop_price IS NOT NULL ORDER BY candle_timestamp""", run_ids)
    for s in cur.fetchall():
        sym = s["symbol"]; entry = Decimal(str(s["entry_reference_price"]))
        target = Decimal(str(s["target_price"])); stop = Decimal(str(s["stop_price"]))
        qty = int(TRADE_CAPITAL/entry)
        if qty == 0: continue
        is_long = target >= entry
        lst, idx = candles(sym)
        start = idx.get(s["candle_timestamp"])
        if start is None: continue
        exit_price = None; exit_time = None; won = False
        for i in range(start+1, len(lst)):
            ot, h, l, c = lst[i]
            if is_long:
                if l <= stop: exit_price, exit_time = stop, ot; break
                if h >= target: exit_price, exit_time, won = target, ot, True; break
            else:
                if h >= stop: exit_price, exit_time = stop, ot; break
                if l <= target: exit_price, exit_time, won = target, ot, True; break
            if ot.hour == 10 and ot.minute == 0: exit_price, exit_time = c, ot; break
        if exit_price is None:
            continue
        gross_pnl = (exit_price-entry)*qty if is_long else (entry-exit_price)*qty
        pnl = gross_pnl - ROUND_TRIP_COST   # net of transaction cost
        won = pnl > 0
        all_trades.append((s["candle_timestamp"], exit_time, pnl, strat, won, sym))

# ---- gross (uncapped) ----
gross = sum(t[2] for t in all_trades)
gross_win = sum(1 for t in all_trades if t[4])
for t in all_trades:
    ps = per_strat[t[3]]; ps["n"]+=1; ps["pnl"]+=t[2]
    if t[4]: ps["win"]+=1
    else: ps["loss"]+=1

# ---- portfolio sim: 15 concurrent slots, ONE position per symbol ----
# all_trades entries: (entry_time, exit_time, pnl, strategy, won, symbol)
all_trades.sort(key=lambda t: t[0])
open_by_symbol = {}   # symbol -> exit_time of its currently-open position
taken=0; skipped_slots=0; skipped_symbol=0; cap_pnl=Decimal(0); cap_win=0
cap_strat = defaultdict(lambda: {"n":0,"pnl":Decimal(0),"win":0})
for entry_t, exit_t, pnl, strat, won, sym in all_trades:
    # free positions that have exited by now
    for s in [s for s,xt in open_by_symbol.items() if xt <= entry_t]:
        del open_by_symbol[s]
    if sym in open_by_symbol:
        skipped_symbol += 1          # already holding this symbol
        continue
    if len(open_by_symbol) >= MAX_POS:
        skipped_slots += 1           # all 15 slots full
        continue
    open_by_symbol[sym] = exit_t
    taken += 1; cap_pnl += pnl
    cs = cap_strat[strat]; cs["n"]+=1; cs["pnl"]+=pnl
    if won: cap_win += 1; cs["win"]+=1

print("="*66)
print(f"PORTFOLIO BACKTEST — {LABEL}  | ₹5,000/trade | {MAX_POS} max concurrent")
print(f"  range {RANGE_START[:10]} → {RANGE_END[:10]}")
print("="*66)
print("\nPer-strategy (uncapped, every signal taken):")
for st in STRATEGIES:
    d = per_strat[st]
    if d["n"]==0:
        print(f"  {st:20s}: no signals"); continue
    wr = d["win"]/d["n"]*100
    print(f"  {st:20s}: {d['n']:>5} trades  win {wr:4.1f}%  P&L ₹{d['pnl']:>10,.0f}")
print(f"\n  GROSS (all {len(all_trades)} signals, no cap): ₹{gross:,.0f}  win {gross_win/max(1,len(all_trades))*100:.1f}%")
print(f"\nWITH 15-POSITION CAP + ONE-PER-SYMBOL (realistic):")
print(f"  trades taken: {taken}   skipped(already holding symbol): {skipped_symbol}   skipped(15 slots full): {skipped_slots}")
if taken:
    print(f"  win rate: {cap_win/taken*100:.1f}%")
print("  per-strategy contribution (capped):")
for st in STRATEGIES:
    d = cap_strat[st]
    if d["n"]: print(f"    {st:20s}: {d['n']:>4} trades  ₹{d['pnl']:>9,.0f}")
print(f"  *** NET P&L ({LABEL}, ₹75k capital): ₹{cap_pnl:,.0f} ***")
if WHICH=="MO":
    print(f"  *** Return on ₹75k: {cap_pnl/Decimal('75000')*100:.1f}% for the month ***")
cur.close(); conn.close()
