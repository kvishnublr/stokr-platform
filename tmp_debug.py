import json, subprocess, sys

# Check 1: What does the backend return for each underlying?
for underlying in ['NIFTY', 'BANKNIFTY', 'MIDCPNIFTY', 'FINNIFTY']:
    result = subprocess.run(
        ['curl', '-s', f'http://localhost:8080/api/option-arbitrage/today?date=2026-07-17&underlying={underlying}'],
        capture_output=True, text=True
    )
    try:
        d = json.loads(result.stdout)
        count = d.get('totalOpportunities', 0)
        opps = d.get('opportunities', [])
        print(f"{underlying}: {count} opportunities")
        for o in opps:
            print(f"  {o['type']} strike={o['strike']} edge={o.get('edgeAfterCosts',0):.0f}")
    except:
        print(f"{underlying}: ERROR parsing response: {result.stdout[:200]}")

# Check 2: What does /scan return for NIFTY?
print("\n--- Scanning NIFTY directly ---")
result = subprocess.run(
    ['curl', '-s', '-X', 'POST', 'http://localhost:8080/api/option-arbitrage/scan', '-d', 'underlying=NIFTY'],
    capture_output=True, text=True
)
try:
    d = json.loads(result.stdout)
    print(f"Scan result: {d.get('totalOpportunities', 0)} opportunities")
    for o in d.get('opportunities', []):
        print(f"  {o['type']} strike={o['strike']} edge={o.get('edgeAfterCosts',0):.0f}")
except:
    print(f"ERROR: {result.stdout[:500]}")

print("\n--- Scanning BANKNIFTY directly ---")
result = subprocess.run(
    ['curl', '-s', '-X', 'POST', 'http://localhost:8080/api/option-arbitrage/scan', '-d', 'underlying=BANKNIFTY'],
    capture_output=True, text=True
)
try:
    d = json.loads(result.stdout)
    print(f"Scan result: {d.get('totalOpportunities', 0)} opportunities")
    for o in d.get('opportunities', []):
        print(f"  {o['type']} strike={o['strike']} edge={o.get('edgeAfterCosts',0):.0f}")
except:
    print(f"ERROR: {result.stdout[:500]}")
