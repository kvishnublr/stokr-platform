import json, sys
fname = sys.argv[1] if len(sys.argv) > 1 else "/tmp/bpscan.json"
d = json.load(open(fname))
opps = d.get("opportunities", [])
print(f"Total: {len(opps)}")
for o in opps[:15]:
    print(f"{o.get('underlying')} | {o.get('strategyType')} | {o.get('strike')} | edge={o.get('edgeAfterCosts')} | action={o.get('action')}")
