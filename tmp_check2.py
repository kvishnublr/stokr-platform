import json

for name, path in [("BANKNIFTY", "/tmp/bn_test.json"), ("NIFTY", "/tmp/nifty_test.json")]:
    with open(path) as f:
        d = json.load(f)
    print(f"\n=== {name}: {d.get('totalOpportunities', 0)} opportunities ===")
    for o in d.get('opportunities', []):
        print(f"  {o['type']} strike={o['strike']} edge={o.get('edgeAfterCosts',0):.0f} CE={o.get('cePrice',0)} PE={o.get('pePrice',0)}")
