import json, urllib.request

# Check box spread
resp = urllib.request.urlopen('http://127.0.0.1:8081/api/option-arbitrage/history?strategyType=BOX_SPREAD&startDate=2026-08-06&endDate=2026-08-06&page=0&size=10')
data = json.loads(resp.read())
print(f"BOX SPREAD today: {data.get('totalElements', 0)} signals")
for i in data.get('items', []):
    print(f"  {i['underlying']} {i['strike']} edge={i['edgeAfterCosts']} status={i['status']}")

# Check what edges look like
resp2 = urllib.request.urlopen('http://127.0.0.1:8081/api/option-arbitrage/history?strategyType=BOX_SPREAD&startDate=2026-08-05&endDate=2026-08-06&page=0&size=20')
data2 = json.loads(resp2.read())
print(f"\nBOX SPREAD last 2 days: {data2.get('totalElements', 0)} total")
edges = [i['edgeAfterCosts'] for i in data2.get('items', []) if i.get('edgeAfterCosts') is not None]
if edges:
    print(f"  Edge range: min={min(edges)}, max={max(edges)}, avg={sum(edges)/len(edges):.0f}")
    pos = [e for e in edges if e > 0]
    neg = [e for e in edges if e <= 0]
    print(f"  Positive edges: {len(pos)}, Negative edges: {len(neg)}")
