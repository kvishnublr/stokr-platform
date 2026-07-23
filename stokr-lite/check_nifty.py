import subprocess, json
result = subprocess.run(
    ["ssh", "root@173.249.55.84",
     "curl -s --max-time 30 'http://localhost:8081/api/option-arbitrage/bid-parity/scan?underlying=NIFTY&force=true'"],
    capture_output=True, text=True, timeout=60
)
d = json.loads(result.stdout)
print(f"NIFTY opps: {len(d.get('opportunities', []))}")
for opp in d.get('opportunities', [])[:3]:
    print(f"  {opp['strike']} {opp['action']} dev={opp.get('bidParityDev',0):.1f} edge={opp.get('edgeAfterCosts',0):.0f}")
