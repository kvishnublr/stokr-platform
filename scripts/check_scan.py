import json, urllib.request

data = json.loads(urllib.request.urlopen("http://localhost:8081/api/option-arbitrage/scan?underlying=BOTH").read())
for o in data.get("opportunities", []):
    desc = o.get("description", "")
    print(f"{o['strike']:>6} {o['type']:<16} CE={o['cePrice']:>8.1f} PE={o['pePrice']:>8.1f} FUT={o['futuresPrice']:>8.1f} edge={o['edgeAfterCosts']:>8.1f} | {desc[:80]}")
print(f"\nTotal: {data.get('totalOpportunities', 0)} opportunities")
