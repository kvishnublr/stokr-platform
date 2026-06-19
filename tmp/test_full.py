import http.client, json

# Test OPTIONS preflight
conn = http.client.HTTPSConnection("stokr.in", 443)
conn.request("OPTIONS", "/api/auth/login", headers={"Origin": "https://stokr.in", "Access-Control-Request-Method": "POST", "Host": "stokr.in"})
resp = conn.getresponse()
resp.read()
print(f"OPTIONS: {resp.status}")
for k, v in resp.headers.items():
    if "access" in k.lower():
        print(f"  {k}: {v}")

# Test POST login
conn2 = http.client.HTTPSConnection("stokr.in", 443)
body = json.dumps({"email": "admin@stokr.in", "password": "Temp@12345678"})
conn2.request("POST", "/api/auth/login", body=body, headers={"Origin": "https://stokr.in", "Content-Type": "application/json", "Host": "stokr.in"})
resp2 = conn2.getresponse()
data = resp2.read().decode()
d = json.loads(data)
print(f"\nPOST login: {resp2.status}")
if "accessToken" in d:
    print(f"  TOKEN OK: {d['accessToken'][:40]}...")
else:
    print(f"  ERROR: {d.get('error', 'unknown')}")
