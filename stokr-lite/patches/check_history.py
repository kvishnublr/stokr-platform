import json, sys
d = json.load(sys.stdin)
for o in d["opportunities"][:3]:
    print(json.dumps({k:v for k,v in o.items() if k in ["underlying","strike","edgeAfterCosts","pnlAfterCosts","status"]}, indent=2))
