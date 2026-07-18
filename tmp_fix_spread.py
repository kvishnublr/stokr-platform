import subprocess, json

# Clean up old records with zero bid/ask (saved before the fix)
r = subprocess.run([
    "ssh", "root@173.249.55.84",
    "PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -c \"DELETE FROM option_arb_opportunities WHERE pe_bid = 0 AND pe_ask = 0 AND status = 'OPEN'\""
], capture_output=True, text=True)
print("Delete result:", r.stdout.strip(), r.stderr.strip())

# Now trigger fresh scans
for underlying in ['NIFTY', 'BANKNIFTY', 'MIDCPNIFTY', 'FINNIFTY']:
    r = subprocess.run([
        "ssh", "root@173.249.55.84",
        f"curl -s 'http://localhost:8080/api/option-arbitrage/scan?underlying={underlying}'"
    ], capture_output=True, text=True, timeout=60)
    try:
        d = json.loads(r.stdout)
        count = d.get('totalOpportunities', 0)
        opps = d.get('opportunities', [])
        print(f"\n{underlying}: {count} opportunities")
        for o in opps:
            spread = "MISSING" if o.get('peBid', 0) == 0 else f"spread={o.get('peAsk', 0) - o.get('peBid', 0):.1f}"
            print(f"  {o['type']} strike={o['strike']} edge={o.get('edgeAfterCosts',0):.0f} CE_bid={o.get('ceBid',0)} CE_ask={o.get('ceAsk',0)} PE_bid={o.get('peBid',0)} PE_ask={o.get('peAsk',0)} [{spread}]")
    except:
        print(f"{underlying}: ERROR - {r.stdout[:200]}")
