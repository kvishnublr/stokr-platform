import subprocess, json

r = subprocess.run(["curl", "-s",
    "http://localhost:8081/api/option-arbitrage/bid-parity/scan?underlying=ALL&force=true",
    "-H", "Authorization: Bearer stokr-admin-2026"], capture_output=True, text=True)
d = json.loads(r.stdout)
opps = d.get("opportunities", [])
print(f"Total: {len(opps)} opps")
by_u = {}
for o in opps:
    u = o.get("underlying", "?")
    by_u[u] = by_u.get(u, 0) + 1
print(f"By underlying: {by_u}")
for o in opps[:10]:
    print(f"  {o.get('underlying')} {o.get('strike')} {o.get('action')} fut={o.get('futuresPrice')} spot={o.get('spotPrice')} edge={o.get('edgeAfterCosts')} dev={o.get('bidParityDev')}")
