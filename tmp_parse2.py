import json

with open('/tmp/bn_final3.json') as f:
    d = json.load(f)

print(f"BANKNIFTY: {d['totalOpportunities']} opportunities")
for o in d.get('opportunities', []):
    ce_s = o.get('ceAsk', 0) - o.get('ceBid', 0)
    pe_s = o.get('peAsk', 0) - o.get('peBid', 0)
    print(f"  {o['type']} strike={o['strike']} edge={o.get('edgeAfterCosts',0):.0f} "
          f"CE={o.get('ceBid',0)}/{o.get('ceAsk',0)}({ce_s:.1f}) "
          f"PE={o.get('peBid',0)}/{o.get('peAsk',0)}({pe_s:.1f})")
