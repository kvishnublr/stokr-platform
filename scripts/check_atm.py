import json, urllib.request

data = json.loads(urllib.request.urlopen("http://localhost:8081/api/option-arbitrage/scan?underlying=NIFTY").read())
for o in data.get("opportunities", []):
    if o["strike"] == 24200:
        print(json.dumps(o, indent=2))
        break

# Check what futures price is being used
print(f"\nSpot from first opp: {data.get('opportunities', [{}])[0].get('spotPrice', 'N/A')}")
print(f"Futures price: {data.get('opportunities', [{}])[0].get('futuresPrice', 'N/A')}")
print(f"Days to expiry: {data.get('opportunities', [{}])[0].get('daysToExpiry', 'N/A')}")
