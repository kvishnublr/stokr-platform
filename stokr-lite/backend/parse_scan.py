import json, sys
d = json.load(open("/tmp/scan.json"))
opps = d.get("opportunities", [])
print(f"Total: {len(opps)}")
for o in opps[:15]:
    print(f"{o.get('underlying')} | {o.get('strategyType')} | {o.get('strike')} | edge={o.get('edgeAfterCosts')} | action={o.get('action')}")
