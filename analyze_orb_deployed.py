"""
Validate the DEPLOYED ORB end-to-end: take the actual ORB backtest signals
(entry/stop) from the DB and apply the deployed profit-trailing exit
(arm +0.2%, trail 0.5% give-back from peak) on 1m candles.
Reports net P&L (8 bps cost), win%, drawdown — the realistic deployed number.
"""
import psycopg2, psycopg2.extras
from collections import defaultdict
from decimal import Decimal

DB_PW = "root123"
conn = psycopg2.connect(host="localhost", port=5432, dbname="stokr_platform", user="postgres", password=DB_PW)
cur = conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor)
CAP = Decimal("5000"); RS, RE = "2026-05-19 03:45:00+00", "2026-06-12 10:00:00+00"
COST_BPS = Decimal("8"); RT = CAP * COST_BPS / Decimal("10000")
ARM_PCT = Decimal("0.002")      # 0.2% of entry
GIVEBACK_PCT = Decimal("0.005") # 0.5% of entry

_cc = {}
def candles(sym):
    if sym in _cc: return _cc[sym]
    cur.execute("SELECT open_time,high_price,low_price,close_price FROM marketdata_candles WHERE symbol=%s AND timeframe='1m' AND open_time>=%s AND open_time<=%s ORDER BY open_time ASC", (sym, RS, RE))
    rows = cur.fetchall()
    lst = [(r["open_time"], Decimal(str(r["high_price"])), Decimal(str(r["low_price"])), Decimal(str(r["close_price"]))) for r in rows]
    _cc[sym] = (lst, {ot: i for i, (ot, *_) in enumerate(lst)})
    return _cc[sym]

# latest completed ORB run per symbol
cur.execute("""SELECT DISTINCT ON (symbol) id,symbol FROM backtest_runs WHERE strategy_key='OPENING_RANGE_BREAKOUT' AND status='COMPLETED' ORDER BY symbol, created_at DESC""")
runs = cur.fetchall()
run_ids = [str(r["id"]) for r in runs]
ph = ",".join(["%s"] * len(run_ids))
cur.execute(f"""SELECT symbol,signal_type,candle_timestamp,entry_reference_price,stop_price FROM strategy_signals WHERE backtest_run_id::text IN ({ph}) AND entry_reference_price>0 AND stop_price IS NOT NULL ORDER BY candle_timestamp""", run_ids)
sigs = cur.fetchall()

trades = []
for s in sigs:
    sym = s["symbol"]; entry = Decimal(str(s["entry_reference_price"])); stop0 = Decimal(str(s["stop_price"]))
    is_long = (s["signal_type"] or "BUY").upper() == "BUY"
    qty = int(CAP / entry)
    if qty == 0: continue
    lst, idx = candles(sym); start = idx.get(s["candle_timestamp"])
    if start is None: continue
    arm = entry * ARM_PCT; give = entry * GIVEBACK_PCT
    peak = entry; cur_stop = stop0; xp = None; xt = None
    for i in range(start + 1, len(lst)):
        ot, h, l, c = lst[i]
        if is_long:
            if l <= cur_stop: xp, xt = cur_stop, ot; break
            if h - entry > peak - entry: peak = h
            if (peak - entry) >= arm:
                cur_stop = max(cur_stop, peak - give)
        else:
            if h >= cur_stop: xp, xt = cur_stop, ot; break
            if entry - l > entry - peak: peak = l
            if (entry - peak) >= arm:
                cur_stop = min(cur_stop, peak + give)
        if ot.hour == 10 and ot.minute == 0: xp, xt = c, ot; break
    if xp is None:
        xp, xt = lst[-1][3], lst[-1][0]
    gross = (xp - entry) * qty if is_long else (entry - xp) * qty
    trades.append((s["candle_timestamp"], xt, gross - RT, sym))

# portfolio 15 / 1-symbol
trades.sort(key=lambda t: t[0])
osym = {}; net = Decimal(0); wins = 0; taken = 0; eq = Decimal(0); peak = Decimal(0); mdd = Decimal(0)
daily = defaultdict(lambda: Decimal(0)); wsum = Decimal(0); lsum = Decimal(0); wn = 0; ln = 0
for et, xt, p, sym in trades:
    for s in [s for s, x in osym.items() if x <= et]: del osym[s]
    if sym in osym or len(osym) >= 15: continue
    osym[sym] = xt; net += p; taken += 1; daily[et.date()] += p
    if p > 0: wins += 1; wsum += p; wn += 1
    else: lsum += p; ln += 1
    eq += p; peak = max(peak, eq); mdd = min(mdd, eq - peak)
days = len(daily); posd = sum(1 for v in daily.values() if v > 0)
print("=" * 60)
print("DEPLOYED ORB — end-to-end (real signals + deployed trailing exit)")
print(f"  {COST_BPS}bps cost, ₹5k/trade, 15 max, 1/symbol, arm +0.2% trail 0.5%")
print("=" * 60)
print(f"Signals: {len(sigs)}  | Trades taken: {taken}  | Win rate: {wins/taken*100 if taken else 0:.1f}%")
print(f"Avg win: ₹{wsum/wn if wn else 0:.0f}   Avg loss: ₹{lsum/ln if ln else 0:.0f}")
print(f"NET P&L (month): ₹{net:+,.0f}  ({net/Decimal('75000')*100:+.2f}% on ₹75k)")
print(f"MAX drawdown: ₹{mdd:+,.0f}")
print(f"Positive days: {posd}/{days}   Worst day: ₹{min(daily.values()) if daily else 0:+.0f}   Best day: ₹{max(daily.values()) if daily else 0:+.0f}")
cur.close(); conn.close()
