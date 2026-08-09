import json, urllib.request

for u in ['NIFTY', 'BANKNIFTY', 'FINNIFTY', 'MIDCPNIFTY']:
    url = f'http://127.0.0.1:8081/api/option-arbitrage/history?strategyType=BOX_SPREAD&underlying={u}&startDate=2026-08-06&endDate=2026-08-06&page=0&size=5'
    try:
        resp = urllib.request.urlopen(url)
        d = json.loads(resp.read())
        total = d['totalElements']
        items = d['items']
        edges = [i['edgeAfterCosts'] for i in items]
        print(f"{u}: {total} signals, edges: {edges}")
    except Exception as e:
        print(f"{u}: error {e}")
