import json, urllib.request
d = json.dumps({"principal": "admin", "password": "password"}).encode()
r = urllib.request.Request("http://localhost:8080/api/auth/login", data=d, headers={"Content-Type": "application/json"}, method="POST")
resp = urllib.request.urlopen(r).read().decode()
print(resp)
data = json.loads(resp)
token = data["data"]["accessToken"]

headers = {"Authorization": f"Bearer {token}"}

# Health
req = urllib.request.Request("http://localhost:8080/api/admin/health", headers=headers)
print("\n=== HEALTH ===")
print(urllib.request.urlopen(req).read().decode())

# Signals
req = urllib.request.Request("http://localhost:8080/api/admin/signals/stats", headers=headers)
print("\n=== SIGNALS ===")
print(urllib.request.urlopen(req).read().decode())

# Settings
req = urllib.request.Request("http://localhost:8080/api/admin/settings/summary", headers=headers)
print("\n=== SETTINGS ===")
print(urllib.request.urlopen(req).read().decode())

# Ops
req = urllib.request.Request("http://localhost:8080/api/admin/ops/status", headers=headers)
print("\n=== OPS ===")
print(urllib.request.urlopen(req).read().decode())

# Pipeline
req = urllib.request.Request("http://localhost:8080/api/strategies/runtime-metrics/pipeline-status", headers=headers)
print("\n=== PIPELINE ===")
print(urllib.request.urlopen(req).read().decode())

# Risk Dashboard
req = urllib.request.Request("http://localhost:8080/api/admin/risk-dashboard", headers=headers)
print("\n=== RISK ===")
print(urllib.request.urlopen(req).read().decode())

# Readiness
req = urllib.request.Request("http://localhost:8080/api/admin/readiness", headers=headers)
print("\n=== READINESS ===")
print(urllib.request.urlopen(req).read().decode())

# Execution stats
req = urllib.request.Request("http://localhost:8080/api/admin/execution/stats", headers=headers)
print("\n=== EXECUTION STATS ===")
try:
    print(urllib.request.urlopen(req).read().decode())
except:
    print("Not available")
