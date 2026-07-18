import json, subprocess, sys
p = subprocess.run(
    ["ssh", "-o", "ConnectTimeout=10", "root@173.249.55.84",
     "curl -s 'http://localhost:8081/api/option-arbitrage/today'"],
    capture_output=True, text=True, timeout=20
)
d = json.loads(p.stdout)
opps = d.get("opportunities", [])
print(f"Total: {len(opps)}")
dates = set()
for o in opps:
    st = o.get("scanTime") or o.get("detectedAt") or "?"
    dates.add(st[:10])
print("Dates:", sorted(dates))
