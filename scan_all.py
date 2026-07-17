import json, subprocess
result = subprocess.run(['curl', '-s', 'http://localhost:8081/api/option-arbitrage/scan?underlying=ALL'], capture_output=True, text=True, timeout=120)
d = json.loads(result.stdout)
print('Total:', d['totalOpportunities'])
print('Types:', d.get('summary', {}))
for o in d.get('opportunities', []):
    print(f"  {o['underlying']} {o['type']} strike={o['strike']} edge={o['edgeAfterCosts']:.0f} DTE={o['daysToExpiry']:.0f} action={o['action']}")
