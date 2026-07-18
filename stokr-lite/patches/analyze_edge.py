"""
Analyze edge per strike and advise on ITM vs ATM strategy
"""
import json, subprocess, sys, io
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

# Fetch current scan results
p = subprocess.run(
    ["ssh", "-o", "ConnectTimeout=10", "root@173.249.55.84",
     "curl -s 'http://localhost:8081/api/option-arbitrage/scan?force=true'"],
    capture_output=True, text=True, timeout=60
)
data = json.loads(p.stdout)
opps = data.get("opportunities", [])

print("=" * 80)
print("STRIKE-BY-STRIKE EDGE ANALYSIS")
print("=" * 80)

for o in opps:
    u = o.get("underlying", "?")
    strike = o.get("strike", 0)
    edge_pts = o.get("edgePoints", 0)
    edge_rs = o.get("edgeAfterCosts", 0)
    ce_ask = o.get("ceAsk", 0)
    ce_bid = o.get("ceBid", 0)
    pe_ask = o.get("peAsk", 0)
    pe_bid = o.get("peBid", 0)
    spot = o.get("spotPrice", 0)
    fut = o.get("futuresPrice", 0)
    dte = o.get("daysToExpiry", 0)
    
    ce_spread = ce_ask - ce_bid
    pe_spread = pe_ask - pe_bid
    total_spread = ce_spread + pe_spread
    itm_distance = abs(strike - spot) / spot * 100
    
    # Cost to exit (slippage on exit = 2 spreads)
    lot = {"NIFTY": 65, "BANKNIFTY": 30, "MIDCPNIFTY": 120, "FINNIFTY": 60}.get(u, 65)
    exit_cost = total_spread * lot
    
    print(f"\n{u} {strike} | DTE={dte} | Spot={spot:.0f} Fut={fut:.0f}")
    print(f"  CE: {ce_bid:.1f}/{ce_ask:.1f} (spread={ce_spread:.1f}) | PE: {pe_bid:.1f}/{pe_ask:.1f} (spread={pe_spread:.1f})")
    print(f"  Total spread: {total_spread:.1f}pts | ITM distance: {itm_distance:.1f}%")
    print(f"  Edge: {edge_pts:.1f}pts = ₹{edge_rs:.0f}/lot | Exit slippage est: ₹{exit_cost:.0f}")
    print(f"  Net after exit slippage: ₹{edge_rs - exit_cost:.0f}")

print("\n" + "=" * 80)
print("SUMMARY & RECOMMENDATION")
print("=" * 80)

# Group by underlying
nifty = [o for o in opps if o["underlying"] == "NIFTY"]
bn = [o for o in opps if o["underlying"] == "BANKNIFTY"]

if nifty:
    spot = nifty[0]["spotPrice"]
    print(f"\nNIFTY (spot={spot:.0f}):")
    print(f"  7 strikes scanned, all parity breaks")
    best = max(nifty, key=lambda x: x["edgeAfterCosts"])
    worst = min(nifty, key=lambda x: x["edgeAfterCosts"])
    print(f"  Best:  {best['strike']} edge=Rs.{best['edgeAfterCosts']:.0f}/lot (lot=65)")
    print(f"  Worst: {worst['strike']} edge=Rs.{worst['edgeAfterCosts']:.0f}/lot")
    margin = (best['cePrice'] + best['pePrice'] + best['futuresPrice']) * 65
    print(f"  Lot margin ~Rs.{margin:.0f}")
    print(f"  Return/cycle: {best['edgeAfterCosts']/margin*100:.2f}% over {best['daysToExpiry']:.0f} days")
    print(f"  Annualized: {best['edgeAfterCosts']/margin*100/best['daysToExpiry']*365:.1f}%")

if bn:
    spot = bn[0]["spotPrice"]
    print(f"\nBANKNIFTY (spot={spot:.0f}):")
    best = max(bn, key=lambda x: x["edgeAfterCosts"])
    print(f"  1 strike: {best['strike']} edge=Rs.{best['edgeAfterCosts']:.0f}/lot (lot=30)")
    margin = (best['cePrice'] + best['pePrice'] + best['futuresPrice']) * 30
    print(f"  Lot margin ~Rs.{margin:.0f}")
    print(f"  Return/cycle: {best['edgeAfterCosts']/margin*100:.2f}% over {best['daysToExpiry']:.0f} days")
    print(f"  Annualized: {best['edgeAfterCosts']/margin*100/best['daysToExpiry']*365:.1f}%")
