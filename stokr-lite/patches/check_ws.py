import subprocess, json
r = subprocess.run(["curl", "-s", "http://localhost:8081/api/option-arbitrage/bid-parity/live-ticks", "-H", "Authorization: Bearer stokr-admin-2026"], capture_output=True, text=True)
d = json.loads(r.stdout)
print("wsCount:", d.get("wsCount", 0))
print("tickCount:", d.get("count", 0))
ws = d.get("wsTicks", {})
keys = list(ws.keys())[:3]
for k in keys:
    print(k, ws[k])

r2 = subprocess.run(["curl", "-s", "http://localhost:8081/api/option-arbitrage/bid-parity/auto-status", "-H", "Authorization: Bearer stokr-admin-2026"], capture_output=True, text=True)
s = json.loads(r2.stdout)
print("\nAuto Status:", json.dumps(s, indent=2))
