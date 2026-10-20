"""F&O concurrency / streak / capital report from live_positions rows (JSON on stdin).
Prints aggregates only. Margin model mirrors OptionArbAutoExecService:
  multi-leg  = strike span x lot size x lots x 1.15, floored at a realistic broker
               hedged-margin per lot (SPAN + exposure on the short legs)
  bid parity = hedged FUT+CE+PE estimate (NIFTY 1.5L, BANKNIFTY 2.5L per lot) x 1.15
"""
import json, sys, collections
from datetime import datetime

rows = json.load(sys.stdin) or []
HEDGED = {"NIFTY": 150000, "BANKNIFTY": 250000, "FINNIFTY": 200000, "MIDCPNIFTY": 180000}
FLOOR = {"NIFTY": 50000, "BANKNIFTY": 60000}            # per lot, hedged multi-leg
DONE = {"CLOSED", "EXITED"}

def ts(s): return datetime.fromisoformat(s[:19]) if s else None

def margin(r):
    und = (r.get("underlying") or "NIFTY").upper()
    lots = r.get("lots") or 1
    lot = r.get("lot_size") or (30 if und == "BANKNIFTY" else 75)
    legs = r.get("legs") or []
    strikes = [l.get("strike") for l in legs if isinstance(l.get("strike"), (int, float))]
    if (r.get("strategy_type") or "").startswith("BID_PARITY") or not legs:
        return HEDGED.get(und, 200000) * lots * 1.15
    span = (max(strikes) - min(strikes)) if len(strikes) > 1 else 0
    return max(span * lot * lots * 1.15, FLOOR.get(und, 60000) * lots)

T = []
for r in rows:
    r["legs"] = json.loads(r["legs_json"]) if r.get("legs_json") else []
    r["en"], r["ex"] = ts(r.get("entered_at")), ts(r.get("exited_at"))
    if r["en"]: T.append(r)
T.sort(key=lambda r: r["en"])
closed = [r for r in T if r.get("status") in DONE and r["ex"]]
print(f"Trades: {len(T)} total, {len(closed)} closed  |  period {T[0]['en']:%d-%b-%Y} to {T[-1]['en']:%d-%b-%Y}"
      f"  |  trading days with entries: {len({r['en'].date() for r in T})}")
brokers = collections.Counter(r.get("broker") or "?" for r in T)
print("By broker:", dict(brokers))

# --- concurrency: sweep entries/exits ---
ev = []
for r in closed:
    m = margin(r); ml = r.get("max_loss_amount") or 0
    ev.append((r["en"], 1, m, ml, r)); ev.append((r["ex"], -1, -m, -ml, r))
ev.sort(key=lambda e: (e[0], e[1]))           # exits before entries at same instant
cur = cm = cml = 0; best = (0, None); bestm = (0, None); open_set = set()
per_day = collections.defaultdict(int); peak_mix = None
for t, d, m, ml, r in ev:
    cur += d; cm += m; cml += ml
    open_set.add(id(r)) if d > 0 else open_set.discard(id(r))
    per_day[t.date()] = max(per_day[t.date()], cur)
    if cur > best[0]:
        best = (cur, t)
        peak_mix = collections.Counter(f"{x['strategy_type']}/{x['underlying']}" for x in closed if id(x) in open_set)
    if cm > bestm[0]: bestm = (cm, t)
print()
print(f"MAX POSITIONS OPEN AT THE SAME TIME: {best[0]}  (first at {best[1]:%d-%b %H:%M})")
print("  open at that moment:", dict(peak_mix))
days = sorted(per_day.values())
print(f"  per-day peak open: median {days[len(days)//2]}, 90th pct {days[int(len(days)*0.9)-1]}, max {days[-1]}")
print("  days by peak open -> count:", dict(sorted(collections.Counter(days).items())))
print(f"PEAK MARGIN IN USE (estimated): Rs {bestm[0]:,.0f}  at {bestm[1]:%d-%b %H:%M}")

# --- per strategy ---
print()
print(f"{'strategy':24} {'n':>4} {'win%':>5} {'total':>9} {'avg':>7} {'worst':>7} {'maxLossStreak':>13} {'margin/trade':>12}")
by = collections.defaultdict(list)
for r in closed: by[r["strategy_type"]].append(r)
for s, rs in sorted(by.items(), key=lambda kv: -len(kv[1])):
    p = [float(r.get("current_pnl") or 0) for r in sorted(rs, key=lambda r: r["ex"])]
    st = mx = 0
    for v in p:
        st = st + 1 if v < 0 else 0; mx = max(mx, st)
    mg = sum(margin(r) for r in rs) / len(rs)
    print(f"{s:24} {len(p):>4} {100*sum(v>0 for v in p)/len(p):>5.0f} {sum(p):>9,.0f} {sum(p)/len(p):>7,.0f} {min(p):>7,.0f} {mx:>13} {mg:>12,.0f}")

# --- streaks & drawdown across all F&O (by exit time) ---
seq = sorted(closed, key=lambda r: r["ex"])
pn = [float(r.get("current_pnl") or 0) for r in seq]
def streak(cond):
    best = cur = 0; run = worst = 0.0
    for v in pn:
        if cond(v): cur += 1; run += v
        else: cur = 0; run = 0.0
        best = max(best, cur); worst = min(worst, run)
    return best, worst
ls, la = streak(lambda v: v < 0); ws, _ = streak(lambda v: v > 0)
eq = peak = dd = 0
for v in pn:
    eq += v; peak = max(peak, eq); dd = min(dd, eq - peak)
daily = collections.defaultdict(float)
for r, v in zip(seq, pn): daily[r["ex"].date()] += v
print()
print(f"MAX CONSECUTIVE LOSING TRADES: {ls}  (worst losing run Rs {la:,.0f})")
print(f"MAX CONSECUTIVE WINNING TRADES: {ws}")
print(f"Net P&L Rs {sum(pn):,.0f} | win rate {100*sum(v>0 for v in pn)/len(pn):.0f}% | max drawdown Rs {dd:,.0f}")
print(f"Worst day Rs {min(daily.values()):,.0f} | best day Rs {max(daily.values()):,.0f} | losing days {sum(v<0 for v in daily.values())}/{len(daily)}")
dl = sorted(daily.values()); st = mx = 0
for d in sorted(daily):
    st = st + 1 if daily[d] < 0 else 0; mx = max(mx, st)
print(f"Max consecutive losing days: {mx}")
