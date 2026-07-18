import json, subprocess, time

r = subprocess.run(['curl', '-sk', '--max-time', '90', 'https://localhost/api/option-arbitrage/scan?underlying=BANKNIFTY'], capture_output=True, text=True, timeout=120)
d = json.loads(r.stdout) if r.stdout.strip() else {"error": "empty"}

for o in d.get('opportunities', [])[:5]:
    strike = o.get('strike', 0)
    ceBid = o.get('ceBid', 0)
    ceAsk = o.get('ceAsk', 0)
    peBid = o.get('peBid', 0)
    peAsk = o.get('peAsk', 0)
    ceSpread = ceAsk - ceBid
    peSpread = peAsk - peBid
    edgeAfter = o.get('edgeAfterCosts', 0)
    
    ceSpreadPct = (ceSpread / ceAsk * 100) if ceAsk > 0 else 0
    peSpreadPct = (peSpread / peAsk * 100) if peAsk > 0 else 0
    
    print(f"strike={strike} edge={edgeAfter:.0f}")
    print(f"  CE: bid={ceBid} ask={ceAsk} spread={ceSpread:.2f} ({ceSpreadPct:.1f}%)")
    print(f"  PE: bid={peBid} ask={peAsk} spread={peSpread:.2f} ({peSpreadPct:.1f}%)")
    print()
