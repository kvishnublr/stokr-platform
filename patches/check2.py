import json, subprocess
r = subprocess.run(['curl', '-s', 'http://localhost:8080/api/option-arbitrage/scan?underlying=BANKNIFTY'], capture_output=True, text=True, timeout=60)
d = json.loads(r.stdout)
print(f"Total: {d.get('totalOpportunities',0)}")
for o in d.get('opportunities', [])[:3]:
    print(f"  {o.get('type')} {o.get('strike')} ceBid={o.get('ceBid',0)} ceAsk={o.get('ceAsk',0)} peBid={o.get('peBid',0)} peAsk={o.get('peAsk',0)} edge={o.get('edgeAfterCosts',0)}")
