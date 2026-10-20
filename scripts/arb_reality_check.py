"""Sanity-check paper Box Spread and Bid Parity fills (JSON rows on stdin).

A box (K1<K2) is worth exactly K2-K1 at expiry, so its fair price today is ~(K2-K1)
discounted at ~6.5%/yr. Bid parity (conversion/reversal) locks |F - K - (C - P)| per unit.
In liquid NSE index options real mispricings are a few points at most; tens of points,
or a "lock-in" that is captured within a minute, mean the fill prices were stale/LTP.
"""
import json, sys, statistics as st
from datetime import datetime

rows = json.load(sys.stdin) or []
def ts(s): return datetime.fromisoformat(s[:19]) if s else None
def num(v):
    try: return float(v)
    except (TypeError, ValueError): return None

print("BOX SPREADS (per unit = per share; x lot size for rupees)")
print(f"{'id':>4} {'und':9} {'width':>5} {'dte':>3} {'fair':>8} {'paid':>8} {'mispr':>6} {'mispr%':>6} {'exitVal':>8} {'hold':>6} {'pnl':>7}  legs-without-exit")
mis = []; holds = []
for r in rows:
    if "BOX" not in (r.get("strategy_type") or ""): continue
    legs = json.loads(r["legs_json"]) if r.get("legs_json") else []
    ks = [num(l.get("strike")) for l in legs if num(l.get("strike"))]
    if len(legs) != 4 or len(set(ks)) != 2:
        print(f"{r['id']:>4} malformed box: {len(legs)} legs"); continue
    width = max(ks) - min(ks)
    en, ex = ts(r["entered_at"]), ts(r["exited_at"])
    dte = max((datetime.fromisoformat(r["expiry_date"]) - en).days, 0) if r.get("expiry_date") else 7
    fair = width / (1 + 0.065 * dte / 365)
    sgn = lambda l: 1 if l.get("side") == "BUY" else -1
    paid = sum(sgn(l) * (num(l.get("entryPrice")) or num(l.get("price")) or 0) for l in legs)
    exitv = sum(sgn(l) * (num(l.get("exitPrice")) or 0) for l in legs)
    noexit = sum(1 for l in legs if not num(l.get("exitPrice")))
    # long box: pay < fair is the edge; short box (net credit): receive > fair
    m = (fair - paid) if paid > 0 else (-paid - fair)
    mis.append(abs(m)); hold = (ex - en).total_seconds() / 60 if ex else None; holds.append(hold or 0)
    print(f"{r['id']:>4} {r['underlying']:9} {width:>5.0f} {dte:>3} {fair:>8.1f} {paid:>8.1f} {m:>6.1f} {100*m/width:>5.1f}% {exitv:>8.1f} {hold or 0:>5.0f}m {float(r.get('current_pnl') or 0):>7.0f}  {noexit}")
if mis:
    print(f"Box: median |mispricing| {st.median(mis):.1f} pts, max {max(mis):.1f} pts; median hold {st.median(holds):.0f} min; "
          f"{sum(h <= 2 for h in holds)}/{len(holds)} closed within 2 min")

print()
print("BID PARITY (conversion/reversal, per unit)")
print(f"{'id':>4} {'und':9} {'K':>6} {'action':28} {'C':>8} {'P':>8} {'F':>9} {'lock':>7} {'C_x':>8} {'P_x':>8} {'F_x':>9} {'hold':>6} {'pnl':>7}")
for r in rows:
    if "BID_PARITY" not in (r.get("strategy_type") or ""): continue
    K = num(r.get("strike")) or 0
    C, P, F = num(r.get("ce_entry_price")), num(r.get("pe_entry_price")), num(r.get("fut_entry_price"))
    Cx, Px, Fx = num(r.get("ce_exit_price")), num(r.get("pe_exit_price")), num(r.get("fut_exit_price"))
    lock = (F - K - (C - P)) if None not in (C, P, F) else None
    en, ex = ts(r["entered_at"]), ts(r["exited_at"])
    hold = (ex - en).total_seconds() / 60 if ex else 0
    f = lambda v, w=8: f"{v:>{w}.1f}" if v is not None else f"{'-':>{w}}"
    print(f"{r['id']:>4} {r['underlying']:9} {K:>6.0f} {str(r.get('action'))[:28]:28} {f(C)} {f(P)} {f(F,9)} {f(lock,7)} {f(Cx)} {f(Px)} {f(Fx,9)} {hold:>5.0f}m {float(r.get('current_pnl') or 0):>7.0f}")
