import json, urllib.request
req = urllib.request.Request('http://localhost:8081/api/option-arbitrage/scan?underlying=BANKNIFTY')
resp = urllib.request.urlopen(req, timeout=15)
data = json.loads(resp.read())
opps = data.get('opportunities', [])
print(f"Count: {len(opps)}")
for o in opps[:2]:
    for k in ['action','underlying','strike','cePrice','pePrice','futuresPrice','ceBid','ceAsk','peBid','peAsk','costBreakdown']:
        print(f"  {k}: {o.get(k)}")
    print("---")
