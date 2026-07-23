import subprocess, json

# Check normal parity scan
r = subprocess.run(["curl", "-s",
    "http://localhost:8081/api/option-arbitrage/scan?underlying=BANKNIFTY&force=true",
    "-H", "Authorization: Bearer stokr-admin-2026"], capture_output=True, text=True)
d = json.loads(r.stdout)
opps = d.get("opportunities", [])
print(f"Normal scan: {len(opps)} opps")
for o in opps[:3]:
    print(f"  {o.get('underlying')} {o.get('strike')} fut={o.get('futuresPrice')} spot={o.get('spotPrice')} edge={o.get('edgeAfterCosts')}")

# Check bid parity scan
r2 = subprocess.run(["curl", "-s",
    "http://localhost:8081/api/option-arbitrage/bid-parity/scan?underlying=BANKNIFTY&force=true",
    "-H", "Authorization: Bearer stokr-admin-2026"], capture_output=True, text=True)
d2 = json.loads(r2.stdout)
opps2 = d2.get("opportunities", [])
print(f"\nBid parity scan: {len(opps2)} opps")
for o in opps2[:3]:
    print(f"  {o.get('underlying')} {o.get('strike')} fut={o.get('futuresPrice')} spot={o.get('spotPrice')} edge={o.get('edgeAfterCosts')} dev={o.get('bidParityDev')}")

# Check NIFTY bid parity
r3 = subprocess.run(["curl", "-s",
    "http://localhost:8081/api/option-arbitrage/bid-parity/scan?underlying=NIFTY&force=true",
    "-H", "Authorization: Bearer stokr-admin-2026"], capture_output=True, text=True)
d3 = json.loads(r3.stdout)
opps3 = d3.get("opportunities", [])
print(f"\nNIFTY bid parity: {len(opps3)} opps")
for o in opps3[:3]:
    print(f"  {o.get('underlying')} {o.get('strike')} fut={o.get('futuresPrice')} spot={o.get('spotPrice')} edge={o.get('edgeAfterCosts')}")
