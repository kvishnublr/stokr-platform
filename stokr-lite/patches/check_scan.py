import json, sys
d = json.load(sys.stdin)
opps = d.get("opportunities", [])
print(len(opps), "opportunities found")
for o in opps[:2]:
    print(f"  {o.get('underlying')} {o.get('strike')} {o.get('action')} edge={o.get('edgeAfterCosts')} pnl={o.get('pnlAfterCosts')} status={o.get('status')}")
