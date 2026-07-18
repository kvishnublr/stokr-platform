"""Scan all 15 stocks for opportunities"""
import subprocess, json, sys, io
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

stocks = ["RELIANCE", "HDFCBANK", "ICICIBANK", "INFY", "TCS", "SBIN", "ITC", 
          "BHARTIARTL", "KOTAKBANK", "LT", "AXISBANK", "TATAMOTORS",
          "HINDUNILVR", "BAJFINANCE", "ADANIENT"]

all_opps = []
for stock in stocks:
    try:
        p = subprocess.run(
            ["ssh", "-o", "ConnectTimeout=10", "root@173.249.55.84",
             f"curl -s 'http://localhost:8081/api/option-arbitrage/scan?underlying={stock}&force=true'"],
            capture_output=True, text=True, timeout=30
        )
        d = json.loads(p.stdout)
        opps = d.get("opportunities", [])
        if opps:
            for o in opps:
                all_opps.append(o)
            print(f"{stock}: {len(opps)} opps")
        else:
            print(f"{stock}: 0 opps")
    except Exception as e:
        print(f"{stock}: ERROR - {e}")

print(f"\n{'='*60}")
print(f"TOTAL: {len(all_opps)} opportunities across {len(stocks)} stocks")
print(f"{'='*60}")

for o in sorted(all_opps, key=lambda x: -x.get("edgeAfterCosts", 0)):
    print(f"  {o['underlying']:12} {o['strike']:>6} {o['type']:12} edge=Rs.{o.get('edgeAfterCosts',0):>6.0f} pts={o.get('edgePoints',0):>5.1f}")
