import json, urllib.request

# Test the raw futures quote API directly
url = "http://localhost:8081/api/option-arbitrage/scan?underlying=NIFTY"
data = json.loads(urllib.request.urlopen(url).read())
opps = data.get("opportunities", [])

if opps:
    o = opps[0]
    print(f"Futures price from scan: {o['futuresPrice']}")
    print(f"Spot price from scan: {o['spotPrice']}")
    print(f"Gap: {o['futuresPrice'] - o['spotPrice']:.1f} pts")
    print(f"DTE: {o['daysToExpiry']}")

# Also check: what's the DTE? 
# If DTE=6, expiry should be Jul 21 (Tue) - but NIFTY expires on Thu
# If DTE=6, could be an error in expiry detection
print()
print("NOTE: NIFTY weekly options expire on Thursday")
print("From Jul 15, DTE=2 = Jul 17 (Thu), DTE=9 = Jul 24 (Thu)")
print("DTE=6 = Jul 21 (Tue) - NOT a standard expiry")
