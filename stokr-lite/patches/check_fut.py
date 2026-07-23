import subprocess, json

# Check what futures price the backend reports
r = subprocess.run(["curl", "-s",
    "http://localhost:8081/api/option-arbitrage/bid-parity/scan?underlying=BANKNIFTY&force=true",
    "-H", "Authorization: Bearer stokr-admin-2026"], capture_output=True, text=True)
d = json.loads(r.stdout)
opps = d.get("opportunities", [])
if opps:
    o = opps[0]
    print(f"Backend futures price: {o.get('futuresPrice')}")
    print(f"Spot price: {o.get('spotPrice')}")
    print(f"Strike: {o.get('strike')}")
    print(f"Underlying: {o.get('underlying')}")
else:
    print("No opps found")

# Also check normal scan
r2 = subprocess.run(["curl", "-s",
    "http://localhost:8081/api/option-arbitrage/scan?underlying=BANKNIFTY&force=true",
    "-H", "Authorization: Bearer stokr-admin-2026"], capture_output=True, text=True)
d2 = json.loads(r2.stdout)
opps2 = d2.get("opportunities", [])
if opps2:
    o2 = opps2[0]
    print(f"\nNormal scan futures: {o2.get('futuresPrice')}")
    print(f"Normal scan spot: {o2.get('spotPrice')}")

# Check spot prices directly
r3 = subprocess.run(["curl", "-s",
    "http://localhost:8081/api/option-arbitrage/health",
    "-H", "Authorization: Bearer stokr-admin-2026"], capture_output=True, text=True)
try:
    d3 = json.loads(r3.stdout)
    print(f"\nHealth: {json.dumps(d3, indent=2)}")
except:
    print(f"\nHealth raw: {r3.stdout[:500]}")
