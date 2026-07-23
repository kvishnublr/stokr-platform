import subprocess, json
r = subprocess.run(["curl", "-s", "http://localhost:8081/api/option-arbitrage/bid-parity/scan?underlying=NIFTY&force=true", "-H", "Authorization: Bearer stokr-admin-2026"], capture_output=True, text=True)
d = json.loads(r.stdout)
opps = d.get("opportunities", [])
print(f"{len(opps)} opportunities")
for o in opps[:3]:
    print(f"  {o.get('underlying')} {o.get('strike')} {o.get('action')}")
    print(f"    ceSymbol={o.get('ceSymbol')} peSymbol={o.get('peSymbol')}")
    print(f"    ceBid={o.get('ceBid')} ceAsk={o.get('ceAsk')} peBid={o.get('peBid')} peAsk={o.get('peAsk')}")
    print(f"    fut={o.get('futuresPrice')} edge={o.get('edgeAfterCosts')}")
