"""
Report daily trade count and PEAK concurrent positions actually reached.
Uses the same realistic portfolio model (15 max, 1/symbol, fixed exits).
"""
import psycopg2, psycopg2.extras
from collections import defaultdict
from decimal import Decimal

DB_PW="33Alu8vwlQpQPMuukjEj9SLrUx14D6PEWSIxga47jSI="
conn=psycopg2.connect(host="localhost",port=5432,dbname="stokr_platform",user="postgres",password=DB_PW)
cur=conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor)
CAP=Decimal("5000"); RS,RE="2026-05-19 04:00:00+00","2026-06-12 10:00:00+00"
CUT="2026-06-14 07:05:00+00"
STRATS=["ADV_CASH","VWAP_SQUEEZE","VWAP_CLOSE_RECLAIM","NIFTY_CATCHUP","VWAP_BOUNCE"]

_cc={}
def candles(sym):
    if sym in _cc: return _cc[sym]
    cur.execute("SELECT open_time,high_price,low_price,close_price FROM marketdata_candles WHERE symbol=%s AND timeframe='1m' AND open_time>=%s AND open_time<=%s ORDER BY open_time ASC",(sym,RS,RE))
    rows=cur.fetchall()
    lst=[(r["open_time"],Decimal(str(r["high_price"])),Decimal(str(r["low_price"])),Decimal(str(r["close_price"]))) for r in rows]
    _cc[sym]=(lst,{ot:i for i,(ot,*_) in enumerate(lst)})
    return _cc[sym]

trades=[]
for strat in STRATS:
    cur.execute("""SELECT DISTINCT ON (symbol) id,symbol FROM backtest_runs WHERE strategy_key=%s AND status='COMPLETED' AND range_start=%s AND range_end=%s AND created_at>=%s ORDER BY symbol,created_at DESC""",(strat,RS,RE,CUT))
    runs=cur.fetchall()
    if not runs: continue
    ph=",".join(["%s"]*len(runs))
    cur.execute(f"""SELECT symbol,candle_timestamp,entry_reference_price,target_price,stop_price FROM strategy_signals WHERE backtest_run_id::text IN ({ph}) AND entry_reference_price>0 AND target_price IS NOT NULL AND stop_price IS NOT NULL ORDER BY candle_timestamp""",[str(r["id"]) for r in runs])
    for s in cur.fetchall():
        sym=s["symbol"]; entry=Decimal(str(s["entry_reference_price"])); tgt=Decimal(str(s["target_price"])); stp=Decimal(str(s["stop_price"]))
        qty=int(CAP/entry)
        if qty==0: continue
        is_long=tgt>=entry
        lst,idx=candles(sym); start=idx.get(s["candle_timestamp"])
        if start is None: continue
        xt=None
        for i in range(start+1,len(lst)):
            ot,h,l,c=lst[i]
            if is_long:
                if l<=stp: xt=ot; break
                if h>=tgt: xt=ot; break
            else:
                if h>=stp: xt=ot; break
                if l<=tgt: xt=ot; break
            if ot.hour==10 and ot.minute==0: xt=ot; break
        if xt is None: continue
        trades.append((s["candle_timestamp"],xt,strat,sym))

trades.sort(key=lambda t:t[0])
open_sym={}; taken_per_day=defaultdict(int); peak_concurrent=0
# rebuild concurrent state walking through time
events=[]
for et,xt,strat,sym in trades:
    events.append((et,"open",sym,strat))
    events.append((xt,"close",sym,strat))
# enforce caps inline
open_sym={}; daily=defaultdict(int); total=0
for et,xt,strat,sym in trades:
    for s in [s for s,x in open_sym.items() if x<=et]: del open_sym[s]
    if sym in open_sym or len(open_sym)>=15: continue
    open_sym[sym]=xt; total+=1
    daily[et.date()]+=1
    if len(open_sym)>peak_concurrent: peak_concurrent=len(open_sym)

trading_days = len(daily)
print(f"Backtest period: 2026-05-19 → 2026-06-12 ({trading_days} trading days)")
print(f"Total trades taken (after 15-cap + 1/symbol): {total}")
print(f"AVG trades per day: {total/trading_days:.1f}")
print(f"MAX trades in any single day: {max(daily.values())}")
print(f"MIN trades in any single day: {min(daily.values())}")
print(f"PEAK concurrent positions ever reached: {peak_concurrent} (cap = 15)")
print(f"\nDaily trade distribution (top 5 busiest days):")
for d,n in sorted(daily.items(), key=lambda x:-x[1])[:5]:
    print(f"  {d}: {n} trades")
print(f"\nQuietest days:")
for d,n in sorted(daily.items(), key=lambda x:x[1])[:5]:
    print(f"  {d}: {n} trades")
cur.close(); conn.close()
