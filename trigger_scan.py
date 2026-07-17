#!/usr/bin/env python3
import subprocess, json, sys

# First check if backend is up
result = subprocess.run(
    ['ssh', 'root@173.249.55.84',
     "curl -s -o /dev/null -w '%{http_code}' 'http://localhost:8080/api/option-arbitrage/health'"],
    capture_output=True, text=True, timeout=30
)
print(f"Health check: {result.stdout}")

# Now trigger scan
print("Triggering scan... (may take 60-90s for all 4 underlyings)")
result = subprocess.run(
    ['ssh', 'root@173.249.55.84',
     "curl -s --max-time 180 'http://localhost:8080/api/option-arbitrage/scan?underlying=ALL'"],
    capture_output=True, text=True, timeout=200
)

print(f"Response length: {len(result.stdout)}")
if not result.stdout:
    print(f"Stderr: {result.stderr[:500]}")

try:
    data = json.loads(result.stdout)
    print(f"Status: {data.get('status')}")
    print(f"Total opportunities: {data.get('totalOpportunities', 0)}")
    summary = data.get('summary', {})
    for k, v in summary.items():
        print(f"  {k}: {v}")
    
    opps = data.get('opportunities', [])
    if opps:
        print(f"\nSample futures prices (first 10):")
        for opp in opps[:10]:
            u = opp.get('underlying', '?')
            s = opp.get('strike', '?')
            f = opp.get('futuresPrice', 0)
            sp = opp.get('spotPrice', 0)
            dev = ((f - sp) / sp * 100) if sp > 0 else 0
            e = opp.get('edgeAfterCosts', 0)
            print(f"  {u:12s} {s:6d}: spot={sp:10.2f} fut={f:10.2f} dev={dev:+.3f}% edge_after_costs={e:.0f}")
except Exception as e:
    print(f"Parse error: {e}")
    print(f"Raw (first 500): {result.stdout[:500]}")
