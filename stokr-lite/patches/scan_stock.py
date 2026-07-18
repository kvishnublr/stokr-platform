"""Quick scan test for a single stock"""
import subprocess, json, sys, io
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

stock = sys.argv[1] if len(sys.argv) > 1 else "RELIANCE"
p = subprocess.run(
    ["ssh", "-o", "ConnectTimeout=10", "root@173.249.55.84",
     f"curl -s 'http://localhost:8081/api/option-arbitrage/scan?underlying={stock}&force=true'"],
    capture_output=True, text=True, timeout=60
)
try:
    d = json.loads(p.stdout)
    opps = d.get("opportunities", [])
    print(f"=== {stock} === {len(opps)} opportunities found")
    for o in opps[:10]:
        print(f"  {o['underlying']} {o['strike']} {o['type']} edge=Rs.{o['edgeAfterCosts']:.0f} pts={o['edgePoints']:.1f}")
except Exception as e:
    print(f"Error: {e}")
    print(p.stdout[:500] if p.stdout else p.stderr[:500])
