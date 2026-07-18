import json, subprocess

r = subprocess.run(['curl', '-s', 'http://localhost:8080/api/option-arbitrage/scan?underlying=BANKNIFTY'], capture_output=True, text=True, timeout=60)
d = json.loads(r.stdout)
print(f"Total: {d.get('totalOpportunities',0)} opps")
types = {}
for o in d.get('opportunities', []):
    t = o.get('type', 'UNKNOWN')
    types[t] = types.get(t, 0) + 1
    if o.get('ceBid', 0) > 0:
        print(f"  {t} strike={o.get('strike')} ceBid={o.get('ceBid')} ceAsk={o.get('ceAsk')} peBid={o.get('peBid')} peAsk={o.get('peAsk')}")
print(f"\nType breakdown: {types}")
# Show first PARITY_BREAK if any
for o in d.get('opportunities', []):
    if o.get('type') == 'PARITY_BREAK':
        print(f"\nFirst PARITY_BREAK: strike={o.get('strike')} ceBid={o.get('ceBid')} ceAsk={o.get('ceAsk')} peBid={o.get('peBid')} peAsk={o.get('peAsk')} edge={o.get('edgeAfterCosts')}")
        break
