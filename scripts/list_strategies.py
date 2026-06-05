#!/usr/bin/env python3
"""Get all strategies and their execution configs to enable live trading."""
import urllib.request, json
BASE = "http://localhost:8080"
token = json.load(urllib.request.urlopen(urllib.request.Request(
    BASE+"/api/auth/login",
    data=json.dumps({"principal":"admin","password":"admin123"}).encode(),
    headers={"Content-Type":"application/json"})))["data"]["accessToken"]
H = {"Authorization": "Bearer "+token}

# Get execution configs
print("=== Strategy Execution Configs ===")
r = json.load(urllib.request.urlopen(urllib.request.Request(BASE+"/api/admin/strategy-execution-configs", headers=H)))
configs = r.get("data", [])
for c in configs:
    print(f"  [{c.get('id','?')[:8]}] {c.get('strategyKey'):20} enabled={c.get('enabled')} live={c.get('liveEnabled')} paper={c.get('paperEnabled')} mode={c.get('executionMode')}")

# Get strategy validation diagnostics to see lifecycle stage
print("\n=== Strategy Validation ===")
try:
    r2 = json.load(urllib.request.urlopen(urllib.request.Request(BASE+"/api/admin/strategy-validation/diagnostics", headers=H)))
    rows = r2.get("data", {}).get("rows", [])
    for row in rows[:20]:
        print(f"  {row.get('strategyKey','?'):20} status={row.get('validationStatus','?')} liveShadow={row.get('liveShadowEnabled')}")
except Exception as e:
    print("Error:", e)
