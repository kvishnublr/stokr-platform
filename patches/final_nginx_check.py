import json, subprocess
r = subprocess.run(['ssh', 'root@173.249.55.84', 'curl -sk --max-time 90 "https://localhost/api/option-arbitrage/scan?underlying=BANKNIFTY"'], capture_output=True, text=True, timeout=120)
d = json.loads(r.stdout) if r.stdout.strip() else {"error": "empty"}
print(f"Count: {d.get('count', len(d.get('opportunities', [])))}")
if d.get('opportunities'):
    o = d['opportunities'][0]
    print(f"First: {o.get('type')} {o.get('strike')} ceBid={o.get('ceBid',0)} ceAsk={o.get('ceAsk',0)} peBid={o.get('peBid',0)} peAsk={o.get('peAsk',0)} edge={o.get('edgeAfterCosts',0)}")
