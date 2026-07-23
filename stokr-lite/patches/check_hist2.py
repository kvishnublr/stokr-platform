import json, sys
d = json.load(sys.stdin)
for o in d["opportunities"][:3]:
    print(json.dumps({k:v for k,v in o.items() if k in ["underlying","strike","strategyType","edgeAfterCosts","pnlAfterCosts","status","expiryDate"]}, indent=2))
