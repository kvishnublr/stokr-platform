import http.client, json

conn = http.client.HTTPConnection("127.0.0.1", 8070)
body = json.dumps({"email": "admin@stokr.in", "password": "Temp@12345678"})
conn.request("POST", "/api/auth/login", body=body, headers={"Content-Type": "application/json"})
resp = conn.getresponse()
data = json.loads(resp.read().decode())
token = data["accessToken"]

conn2 = http.client.HTTPConnection("127.0.0.1", 8070)
conn2.request("GET", "/api/strategies", headers={"Authorization": f"Bearer {token}"})
resp2 = conn2.getresponse()
strategies = json.loads(resp2.read().decode())
print("=== STRATEGIES ===")
for s in strategies:
    print(f"ID={s.get('id')} type={s.get('strategyType')} enabled={s.get('enabled')} name={s.get('name')}")

# Get strategy configs
conn4 = http.client.HTTPConnection("127.0.0.1", 8070)
conn4.request("GET", "/api/admin/strategy-configs", headers={"Authorization": f"Bearer {token}"})
resp4 = conn4.getresponse()
if resp4.status == 200:
    configs = json.loads(resp4.read().decode())
    print("\n=== STRATEGY CONFIGS ===")
    for c in configs:
        print(f"strategyId={c.get('strategyId')} params={c.get('params')}")
else:
    print(f"\nConfigs endpoint: {resp4.status}")

conn3 = http.client.HTTPConnection("127.0.0.1", 8070)
conn3.request("GET", "/api/admin/strategy-mappings", headers={"Authorization": f"Bearer {token}"})
resp3 = conn3.getresponse()
if resp3.status == 200:
    mappings = json.loads(resp3.read().decode())
    print("\n=== STRATEGY MAPPINGS ===")
    for m in mappings:
        print(f"scanner={m.get('scannerName')} strategyId={m.get('strategyId')} enabled={m.get('enabled')}")
else:
    print(f"\nMappings endpoint: {resp3.status}")
