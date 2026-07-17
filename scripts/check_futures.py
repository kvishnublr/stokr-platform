import json, urllib.request

# Check the raw NIFTY futures quote directly
data = json.loads(urllib.request.urlopen("http://localhost:8081/api/option-arbitrage/scan?underlying=NIFTY").read())
opps = data.get("opportunities", [])
if opps:
    o = opps[10]  # ATM-ish
    synthetic = o["strike"] + (o["cePrice"] - o["pePrice"])  # simplified
    print(f"ATM Strike: {o['strike']}")
    print(f"CE: {o['cePrice']}, PE: {o['pePrice']}")
    print(f"C-P = {o['cePrice'] - o['pePrice']:.1f}")
    print(f"Synthetic (simplified): {synthetic:.1f}")
    print(f"Futures: {o['futuresPrice']}")
    print(f"Spot: {o['spotPrice']}")
    print(f"Futures vs Spot: {o['futuresPrice'] - o['spotPrice']:.1f} pts")
    print(f"Synthetic vs Futures: {synthetic - o['futuresPrice']:.1f} pts")
    print(f"DTE: {o['daysToExpiry']}")

# Key question: is futures 24024 correct or stale?
# For NIFTY at 24195, fair futures = 24195 * e^(0.065 * DTE/365)
import math
for dte in [1, 6, 15, 30]:
    fair = 24195 * math.exp(0.065 * dte / 365)
    print(f"  Fair futures for {dte} DTE: {fair:.1f} (premium: {fair-24195:.1f} pts)")
