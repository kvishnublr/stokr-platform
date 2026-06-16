"""
Test REVERSING ADV_CASH: for every BUY signal, take SELL instead; for every
SELL, take BUY. Stop and target swap (mirror around entry).

Reports gross vs net (8 bps cost), win rate, P&L, drawdown — vs ORIGINAL.
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

_cc = {}
def candles(sym):
    if sym in _cc: return _cc[sym]
    cur.execute("SELECT open_time,high_price,low_price,close_price FROM marketdata_candles WHERE symbol=%s AND timeframe='1m' AND open_time>=%s AND open_time<=%s ORDER BY open_time ASC", (sym, RS, RE))
    rows = cur.fetchall()
    lst = [(r["open_time"], Decimal(str(r["high_price"])), Decimal(str(r["low_price"])), Decimal(str(r["close_price"]))) for r in rows]
    _cc[sym] = (lst, {ot: i for i, (ot, *_) in enumerate(lst)})
    return _cc[sym]

# load ADV_CASH signals
cur.execute("""SELECT DISTINCT ON (symbol) id,symbol FROM backtest_runs WHERE strategy_key='ADV_CASH' AND status='COMPLETED' AND range_start=%s AND range_end=%s AND created_at>=%s ORDER BY symbol,created_at DESC""", (RS, RE, CUT))
runs = cur.fetchall()
ph = ",".join(["%s"] * len(runs))
cur.execute(f"""SELECT symbol,candle_timestamp,entry_reference_price,target_price,stop_price FROM strategy_signals WHERE backtest_run_id::text IN ({ph}) AND entry_reference_price>0 AND target_price IS NOT NULL AND stop_price IS NOT NULL ORDER BY candle_timestamp""", [str(r["id"]) for r in runs])
raw = cur.fetchall()

def simulate(reverse):
    """If reverse=True, flip direction (mirror stop/target around entry)."""
    trades = []
    for s in raw:
        sym = s["symbol"]; entry = Decimal(str(s["entry_reference_price"]))
        tgt = Decimal(str(s["target_price"])); stp = Decimal(str(s["stop_price"]))
        qty = int(CAP / entry)
        if qty == 0: continue
        is_long = tgt >= entry
        if reverse:
            # Mirror: new_target = 2*entry - old_target, new_stop = 2*entry - old_stop
            tgt2 = entry * 2 - tgt
            stp2 = entry * 2 - stp
            tgt, stp = tgt2, stp2
            is_long = not is_long
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
        trades.append((s["candle_timestamp"], xt, gross - RT_COST, gross, sym))
    # portfolio sim, 15 pos / 1 symbol
    trades.sort(key=lambda t: t[0])
    open_sym = {}; net=Decimal(0); gross=Decimal(0); wins=0; taken=0
    eq=Decimal(0); peak=Decimal(0); mdd=Decimal(0); daily=defaultdict(lambda: Decimal(0))
    for et, xt, n, g, sym in trades:
        for s in [s for s, x in open_sym.items() if x <= et]: del open_sym[s]
        if sym in open_sym or len(open_sym) >= 15: continue
        open_sym[sym]=xt; net+=n; gross+=g; taken+=1; daily[et.date()]+=n
        if n>0: wins+=1
        eq+=n; peak=max(peak,eq); mdd=min(mdd, eq-peak)
    return dict(trades=taken, gross=gross, net=net, wins=wins, mdd=mdd, daily=daily)

print("="*60)
print(f"REVERSE ADV_CASH HYPOTHESIS TEST")
print(f"  Same signals, opposite direction, 1m candles, {COST_BPS}bps cost")
print(f"  ₹5k/trade, 15 max conc, 1/symbol")
print("="*60)
print(f"Loaded {len(raw)} ADV_CASH signals over 1 month\n")

for label, rev in [("ORIGINAL", False), ("REVERSED", True)]:
    r = simulate(rev)
    wr = r["wins"]/r["trades"]*100 if r["trades"] else 0
    days = len(r["daily"])
    pos_days = sum(1 for p in r["daily"].values() if p > 0)
    worst = min(r["daily"].values()) if r["daily"] else Decimal(0)
    best = max(r["daily"].values()) if r["daily"] else Decimal(0)
    print(f"[{label}]")
    print(f"  Trades taken (capped): {r['trades']}")
    print(f"  Win rate: {wr:.1f}%")
    print(f"  GROSS P&L: ₹{r['gross']:+,.0f}")
    print(f"  NET P&L (after costs): ₹{r['net']:+,.0f}  ({r['net']/Decimal('75000')*100:+.2f}% on ₹75k)")
    print(f"  MAX equity drawdown: ₹{r['mdd']:+,.0f}")
    print(f"  Positive days: {pos_days}/{days}   Worst day: ₹{worst:+.0f}   Best day: ₹{best:+.0f}\n")

cur.close(); conn.close()
