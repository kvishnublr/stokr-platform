import urllib.request, json

base = 'http://localhost:8081/api/option-arbitrage'

for u in ['NIFTY', 'BANKNIFTY', 'MIDCPNIFTY', 'FINNIFTY']:
    url = f'{base}/scan?force=true&underlying={u}'
    try:
        resp = urllib.request.urlopen(url)
        d = json.loads(resp.read())
        opps = d.get('opportunities', [])
        print(f'{u}: {len(opps)} opportunities')
        for o in opps[:3]:
            print(f"  {o.get('type','?')} strike={o.get('strike','?')} edge={o.get('edgeAfterCosts',0):.0f}")
    except Exception as e:
        print(f'{u}: ERROR - {e}')
