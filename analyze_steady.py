"""
Exit-policy research on EXISTING 1-month signals (no redeploy needed).

Compares, for the steady-income core (excludes ADV_CASH by default):
  - FIXED  : the stored target/stop
  - TRAIL  : initial stop = stored stop, then ratchet a trailing stop behind the
             peak so winners run and capture the trend tail (the one thing that
             beats transaction costs)

Portfolio constraints: 15 max concurrent, ONE position per symbol, ₹5k/trade,
transaction cost in bps, and an optional per-day loss limit (capital protection).
Reports net P&L, win rate, avg win/loss, MAX DRAWDOWN, and monthly return.

Usage: python3 analyze_steady.py <cutoff> <cost_bps> <trail_pct> <daily_loss_limit_rupees> <include_adv:0|1>
"""
import psycopg2, psycopg2.extras, sys, heapq
from collections import defaultdict
from decimal import Decimal

DB_PW = "33Alu8vwlQpQPMuukjEj9SLrUx14D6PEWSIxga47jSI="
conn = psycopg2.connect(host="localhost", port=5432, dbname="stokr_platform",
                        user="postgres", password=DB_PW)
cur = conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor)

TRADE_CAPITAL = Decimal("5000")
MAX_POS = 15
CUTOFF      = sys.argv[1] if len(sys.argv) > 1 else "2026-06-14 07:05:00+00"
COST_BPS    = Decimal(sys.argv[2]) if len(sys.argv) > 2 else Decimal("6")
TRAIL_PCT   = Decimal(sys.argv[3]) if len(sys.argv) > 3 else Decimal("0.004")  # 0.4%
DAY_LOSS    = Decimal(sys.argv[4]) if len(sys.argv) > 4 else Decimal("1500")   # ₹ stop-for-day
INCLUDE_ADV = (len(sys.argv) > 5 and sys.argv[5] == "1")
RT_COST = TRADE_CAPITAL * COST_BPS / Decimal("10000")
RANGE_START, RANGE_END = "2026-05-19 04:00:00+00", "2026-06-12 10:00:00+00"

CORE = ["VWAP_SQUEEZE", "VWAP_CLOSE_RECLAIM", "NIFTY_CATCHUP", "VWAP_BOUNCE"]
STRATEGIES = (["ADV_CASH"] + CORE) if INCLUDE_ADV else CORE

_cc = {}
def candles(sym):
    if sym in _cc: return _cc[sym]
    cur.execute("""SELECT open_time,high_price,low_price,close_price FROM marketdata_candles
                   WHERE symbol=%s AND timeframe='1m' AND open_time>=%s AND open_time<=%s
                   ORDER BY open_time ASC""", (sym, RANGE_START, RANGE_END))
    rows = cur.fetchall()
    lst = [(r["open_time"], Decimal(str(r["high_price"])), Decimal(str(r["low_price"])),
            Decimal(str(r["close_price"]))) for r in rows]
    _cc[sym] = (lst, {ot:i for i,(ot,*_) in enumerate(lst)})
    return _cc[sym]

def simulate(entry, stop, target, is_long, lst, start, policy):
    """Return (exit_price, exit_time) for a policy."""
    trail_dist = entry * TRAIL_PCT
    peak = entry
    cur_stop = stop
    for i in range(start+1, len(lst)):
        ot,h,l,c = lst[i]
        if policy == "FIXED":
            if is_long:
                if l <= stop: return stop, ot
                if h >= target: return target, ot
            else:
                if h >= stop: return stop, ot
                if l <= target: return target, ot
        else:  # TRAIL
            if is_long:
                if l <= cur_stop: return cur_stop, ot
                peak = max(peak, h)
                cur_stop = max(cur_stop, peak - trail_dist)
            else:
                if h >= cur_stop: return cur_stop, ot
                peak = min(peak, l)
                cur_stop = min(cur_stop, peak + trail_dist)
        if ot.hour==10 and ot.minute==0: return c, ot
    return None, None

def collect(policy):
    trades=[]
    for strat in STRATEGIES:
        cur.execute("""SELECT DISTINCT ON (symbol) id,symbol FROM backtest_runs
                       WHERE strategy_key=%s AND status='COMPLETED' AND range_start=%s
                       AND range_end=%s AND created_at>=%s ORDER BY symbol,created_at DESC""",
                    (strat, RANGE_START, RANGE_END, CUTOFF))
        runs=cur.fetchall()
        if not runs: continue
        ph=",".join(["%s"]*len(runs))
        cur.execute(f"""SELECT symbol,candle_timestamp,entry_reference_price,target_price,stop_price
                        FROM strategy_signals WHERE backtest_run_id::text IN ({ph})
                        AND entry_reference_price>0 AND target_price IS NOT NULL AND stop_price IS NOT NULL
                        ORDER BY candle_timestamp""", [str(r["id"]) for r in runs])
        for s in cur.fetchall():
            sym=s["symbol"]; entry=Decimal(str(s["entry_reference_price"]))
            target=Decimal(str(s["target_price"])); stop=Decimal(str(s["stop_price"]))
            qty=int(TRADE_CAPITAL/entry)
            if qty==0: continue
            is_long = target>=entry
            lst,idx=candles(sym); start=idx.get(s["candle_timestamp"])
            if start is None: continue
            xp,xt = simulate(entry,stop,target,is_long,lst,start,policy)
            if xp is None: continue
            gross=(xp-entry)*qty if is_long else (entry-xp)*qty
            trades.append((s["candle_timestamp"], xt, gross-RT_COST, strat, sym))
    return trades

def portfolio(trades):
    trades.sort(key=lambda t:t[0])
    open_sym={}; taken=0; pnl=Decimal(0); wins=0
    day_pnl=defaultdict(lambda:Decimal(0)); per=defaultdict(lambda:{"n":0,"pnl":Decimal(0),"w":0})
    eq=Decimal(0); peak=Decimal(0); maxdd=Decimal(0); wsum=Decimal(0); lsum=Decimal(0); wn=0; ln=0
    for et,xt,p,strat,sym in trades:
        for s in [s for s,x in open_sym.items() if x<=et]: del open_sym[s]
        day=et.date()
        if day_pnl[day] <= -DAY_LOSS:   # daily loss limit hit -> no new trades that day
            continue
        if sym in open_sym: continue
        if len(open_sym)>=MAX_POS: continue
        open_sym[sym]=xt; taken+=1; pnl+=p; day_pnl[day]+=p
        d=per[strat]; d["n"]+=1; d["pnl"]+=p
        if p>0: wins+=1; d["w"]+=1; wsum+=p; wn+=1
        else: lsum+=p; ln+=1
        eq+=p; peak=max(peak,eq); maxdd=min(maxdd, eq-peak)
    return dict(taken=taken,pnl=pnl,wins=wins,maxdd=maxdd,per=per,
                avgw=(wsum/wn if wn else Decimal(0)), avgl=(lsum/ln if ln else Decimal(0)))

print("="*68)
print(f"STEADY-INCOME EXIT RESEARCH | ₹5k/trade | 15 pos | cost {COST_BPS}bps")
print(f"  strategies: {', '.join(STRATEGIES)}")
print(f"  trail={TRAIL_PCT*100}%  daily-loss-stop=₹{DAY_LOSS}")
print("="*68)
for policy in ("FIXED","TRAIL"):
    t=collect(policy); r=portfolio(t)
    wr = r["wins"]/r["taken"]*100 if r["taken"] else 0
    print(f"\n[{policy}]  trades={r['taken']}  win={wr:.1f}%  avgWin=₹{r['avgw']:.0f} avgLoss=₹{r['avgl']:.0f}")
    for st in STRATEGIES:
        d=r["per"][st]
        if d["n"]: print(f"    {st:20s}: {d['n']:>4} tr  ₹{d['pnl']:>8,.0f}")
    print(f"  NET P&L (month): ₹{r['pnl']:,.0f}   ({r['pnl']/Decimal('75000')*100:.1f}% on ₹75k)")
    print(f"  MAX DRAWDOWN: ₹{r['maxdd']:,.0f}")
cur.close(); conn.close()
