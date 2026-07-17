import json
d = json.load(open('/tmp/hist.json'))
print('Status:', d.get('status'))
print('Total:', d.get('total'))
opps = d.get('opportunities', [])
print('Count:', len(opps))
for o in opps[:5]:
    print(f"  {o.get('scanTime','?')[:19]} {o.get('underlying')} {o.get('type')} {o.get('strike')} edge={o.get('edgeAfterCosts')} status={o.get('status')} action={o.get('action')}")
    print(f"    legs={o.get('legs','')[:80]}")
