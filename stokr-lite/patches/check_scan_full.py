import subprocess, json, sys

# Test NIFTY bid parity scan
print("=== NIFTY Bid Parity Scan ===")
r = subprocess.run(["curl", "-s", 
    "http://localhost:8081/api/option-arbitrage/bid-parity/scan?underlying=NIFTY&force=true",
    "-H", "Authorization: Bearer stokr-admin-2026"], capture_output=True, text=True)
try:
    d = json.loads(r.stdout)
    opps = d.get("opportunities", [])
    print(f"Opportunities: {len(opps)}")
    for o in opps[:5]:
        print(f"  {o.get('underlying')} {o.get('strike')} {o.get('action')} ceBid={o.get('ceBid')} peBid={o.get('peBid')} fut={o.get('futuresPrice')} edge={o.get('edgeAfterCosts')} dev={o.get('bidParityDev')}")
        print(f"    ceSymbol={o.get('ceSymbol')} peSymbol={o.get('peSymbol')}")
except Exception as e:
    print(f"ERROR parsing response: {e}")
    print(f"Raw: {r.stdout[:500]}")

# Test ALL underlyings
print("\n=== ALL Bid Parity Scan ===")
r2 = subprocess.run(["curl", "-s",
    "http://localhost:8081/api/option-arbitrage/bid-parity/scan?underlying=ALL&force=true",
    "-H", "Authorization: Bearer stokr-admin-2026"], capture_output=True, text=True)
try:
    d2 = json.loads(r2.stdout)
    opps2 = d2.get("opportunities", [])
    print(f"Total opportunities: {len(opps2)}")
    by_u = {}
    for o in opps2:
        u = o.get("underlying", "?")
        by_u[u] = by_u.get(u, 0) + 1
    print(f"By underlying: {by_u}")
    for o in opps2[:5]:
        print(f"  {o.get('underlying')} {o.get('strike')} {o.get('action')} edge={o.get('edgeAfterCosts')} ceBid={o.get('ceBid')} peBid={o.get('peBid')}")
except Exception as e:
    print(f"ERROR parsing response: {e}")
    print(f"Raw: {r2.stdout[:500]}")

# Test normal scan (non-bid) for comparison
print("\n=== Normal Parity Scan ===")
r3 = subprocess.run(["curl", "-s",
    "http://localhost:8081/api/option-arbitrage/scan?underlying=NIFTY&force=true",
    "-H", "Authorization: Bearer stokr-admin-2026"], capture_output=True, text=True)
try:
    d3 = json.loads(r3.stdout)
    opps3 = d3.get("opportunities", [])
    print(f"Normal parity opps: {len(opps3)}")
    for o in opps3[:3]:
        print(f"  {o.get('underlying')} {o.get('strike')} edge={o.get('edgeAfterCosts')}")
except Exception as e:
    print(f"ERROR: {e}")
    print(f"Raw: {r3.stdout[:500]}")
