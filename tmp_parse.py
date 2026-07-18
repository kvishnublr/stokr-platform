import json

with open('/tmp/bn_final2.json') as f:
    d = json.load(f)

print(f"BANKNIFTY: {d['totalOpportunities']} opportunities")
for o in d.get('opportunities', []):
    ce_spread = o.get('ceAsk', 0) - o.get('ceBid', 0)
    pe_spread = o.get('peAsk', 0) - o.get('peBid', 0)
    print(f"  {o['type']} strike={o['strike']} edge={o.get('edgeAfterCosts',0):.0f} "
          f"CE_bid={o.get('ceBid',0)} CE_ask={o.get('ceAsk',0)} spread={ce_spread:.1f} "
          f"PE_bid={o.get('peBid',0)} PE_ask={o.get('peAsk',0)} spread={pe_spread:.1f}")
