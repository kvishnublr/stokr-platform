import sys, json
d = json.load(sys.stdin)
print(f"Count: {d['count']}")
for o in d.get('opportunities', [])[:8]:
    print(f"  {o['underlying']} {o['strike']} {o['action']} edge=Rs.{o['edgeAfterCosts']:.0f}")
