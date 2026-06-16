"""
Opening-Range-Breakout (ORB) RESEARCH on raw candles — no strategy code, no deploy.

Tests whether a genuine TREND strategy has harvestable edge on the 20-symbol
large-cap universe (where the reversion strategies netted ~0 after costs).

Per symbol per day:
  - Opening range = first OR_MIN minutes (09:15 IST = 03:45 UTC).
  - After the OR, take the FIRST candle that closes beyond OR high/low with
    volume >= VOLX * OR-average volume.
  - Entry at that close. Initial stop = opposite OR edge, capped at MAX_STOP_PCT.
  - Trailing stop (TRAIL_PCT behind peak) so trend days run; exit on trail or EOD.
  - One trade per symbol per day.

Portfolio: ₹5k/trade, 15 max concurrent, cost COST_BPS round-trip.
Reports net P&L, win%, avg win/loss, MAX DRAWDOWN, monthly return.

Usage: python3 orb_research.py <or_min> <volx> <max_stop_pct> <trail_pct> <cost_bps>
"""
import psycopg2, psycopg2.extras, sys
from collections import defaultdict
from decimal import Decimal

DB_PW="33Alu8vwlQpQPMuukjEj9SLrUx14D6PEWSIxga47jSI="
conn=psycopg2.connect(host="localhost",port=5432,dbname="stokr_platform",user="postgres",password=DB_PW)
cur=conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor)

OR_MIN     = int(sys.argv[1]) if len(sys.argv)>1 else 15
VOLX       = Decimal(sys.argv[2]) if len(sys.argv)>2 else Decimal("1.5")
MAX_STOP   = Decimal(sys.argv[3]) if len(sys.argv)>3 else Decimal("0.006")  # 0.6%
TRAIL_PCT  = Decimal(sys.argv[4]) if len(sys.argv)>4 else Decimal("0.005")  # 0.5%
COST_BPS   = Decimal(sys.argv[5]) if len(sys.argv)>5 else Decimal("6")
TRADE_CAP=Decimal("5000"); MAX_POS=15
RT_COST=TRADE_CAP*COST_BPS/Decimal("10000")
RS,RE="2026-05-19 03:45:00+00","2026-06-12 10:00:00+00"
SYMBOLS=['HDFCBANK','ICICIBANK','RELIANCE','INFY','AXISBANK','KOTAKBANK','LT','BAJFINANCE',
 'BHARTIARTL','HCLTECH','MARUTI','ASIANPAINT','HINDUNILVR','NTPC','ONGC','POWERGRID',
 'COALINDIA','BPCL','DRREDDY','BAJAJFINSV']

def D(x): return Decimal(str(x))
trades=[]
for sym in SYMBOLS:
    cur.execute("""SELECT open_time,open_price,high_price,low_price,close_price,volume
                   FROM marketdata_candles WHERE symbol=%s AND timeframe='1m'
                   AND open_time>=%s AND open_time<=%s ORDER BY open_time ASC""",(sym,RS,RE))
    rows=cur.fetchall()
    byday=defaultdict(list)
    for r in rows: byday[r["open_time"].date()].append(r)
    for day,bars in byday.items():
        if len(bars)<OR_MIN+20: continue
        orb=bars[:OR_MIN]
        orh=max(D(b["high_price"]) for b in orb); orl=min(D(b["low_price"]) for b in orb)
        oravg=sum(D(b["volume"]) for b in orb)/len(orb)
        if orh<=0 or oravg<=0: continue
        entered=False
        for i in range(OR_MIN,len(bars)):
            b=bars[i]; c=D(b["close_price"]); v=D(b["volume"])
            if entered: break
            long_brk = c>orh and v>=VOLX*oravg
            short_brk= c<orl and v>=VOLX*oravg
            if not(long_brk or short_brk): continue
            is_long=long_brk; entry=c
            # initial stop = opposite OR edge, capped
            if is_long:
                stop=max(orl, entry*(1-MAX_STOP))
            else:
                stop=min(orh, entry*(1+MAX_STOP))
            qty=int(TRADE_CAP/entry)
            if qty==0: break
            # walk forward with trailing
            peak=entry; cur_stop=stop; trail=entry*TRAIL_PCT
            xp=None;
            for j in range(i+1,len(bars)):
                bb=bars[j]; h=D(bb["high_price"]); l=D(bb["low_price"]); cc=D(bb["close_price"]); ot=bb["open_time"]
                if is_long:
                    if l<=cur_stop: xp=cur_stop; break
                    peak=max(peak,h); cur_stop=max(cur_stop,peak-trail)
                else:
                    if h>=cur_stop: xp=cur_stop; break
                    peak=min(peak,l); cur_stop=min(cur_stop,peak+trail)
                if ot.hour==10 and ot.minute==0: xp=cc; break
            if xp is None: xp=D(bars[-1]["close_price"])
            gross=(xp-entry)*qty if is_long else (entry-xp)*qty
            trades.append((b["open_time"], bars[j]["open_time"] if xp is not None else bars[-1]["open_time"],
                           gross-RT_COST, sym))
            entered=True

# portfolio 15-pos, 1/symbol(natural), drawdown
trades.sort(key=lambda t:t[0])
open_sym={}; taken=0; pnl=Decimal(0); wins=0; eq=Decimal(0); peak=Decimal(0); mdd=Decimal(0)
wsum=Decimal(0); lsum=Decimal(0); wn=0; ln=0
for et,xt,p,sym in trades:
    for s in [s for s,x in open_sym.items() if x<=et]: del open_sym[s]
    if sym in open_sym or len(open_sym)>=MAX_POS: continue
    open_sym[sym]=xt; taken+=1; pnl+=p
    if p>0: wins+=1; wsum+=p; wn+=1
    else: lsum+=p; ln+=1
    eq+=p; peak=max(peak,eq); mdd=min(mdd,eq-peak)
wr=wins/taken*100 if taken else 0
print(f"ORB  or={OR_MIN}m volx={VOLX} maxStop={MAX_STOP*100}% trail={TRAIL_PCT*100}% cost={COST_BPS}bps")
print(f"  trades={taken}  win={wr:.1f}%  avgWin=₹{(wsum/wn if wn else 0):.0f}  avgLoss=₹{(lsum/ln if ln else 0):.0f}")
print(f"  NET P&L (month): ₹{pnl:,.0f}  ({pnl/Decimal('75000')*100:.1f}% on ₹75k)   MAX DD: ₹{mdd:,.0f}")
cur.close(); conn.close()
