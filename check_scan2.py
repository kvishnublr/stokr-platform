import json
d = json.load(open('/tmp/nifty_scan.json'))
print('Status:', d.get('status'))
print('Total:', d.get('totalOpportunities'))
print('Types:', d.get('summary'))
for o in d.get('opportunities', []):
    print(f"  {o['underlying']} {o['type']} strike={o['strike']} edge={o.get('edgeAfterCosts',0):.0f}")
