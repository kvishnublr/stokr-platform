import json, subprocess

for u in ['NIFTY', 'BANKNIFTY', 'MIDCPNIFTY', 'FINNIFTY']:
    r = subprocess.run(
        ['ssh', 'root@173.249.55.84', f"curl -s 'http://localhost:8080/api/option-arbitrage/scan?underlying={u}'"],
        capture_output=True, text=True, timeout=60
    )
    d = json.loads(r.stdout)
    count = d.get('totalOpportunities', 0)
    print(f"\n{u}: {count} opportunities")
    for o in d.get('opportunities', []):
        print(f"  {o['type']} strike={o['strike']} edge={o.get('edgeAfterCosts',0):.0f} "
              f"CE_bid={o.get('ceBid',0)} CE_ask={o.get('ceAsk',0)} "
              f"PE_bid={o.get('peBid',0)} PE_ask={o.get('peAsk',0)}")
