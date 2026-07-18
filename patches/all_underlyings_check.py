import json, subprocess, time
time.sleep(65)

for u in ['NIFTY', 'BANKNIFTY', 'MIDCPNIFTY', 'FINNIFTY']:
    r = subprocess.run(['curl', '-sk', '--max-time', '90', f'https://localhost/api/option-arbitrage/scan?underlying={u}'], capture_output=True, text=True, timeout=120)
    d = json.loads(r.stdout) if r.stdout.strip() else {"error": "empty"}
    count = d.get('count', len(d.get('opportunities', [])))
    first = d.get('opportunities', [{}])[0] if d.get('opportunities') else {}
    cb = first.get('ceBid', 0)
    pa = first.get('peAsk', 0)
    print(f"{u}: {count} opps, first ceBid={cb} peAsk={pa} edge={first.get('edgeAfterCosts', 0)}")
    time.sleep(2)
