import urllib.request, json, sys

def api(method, path, data=None, token=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    body = json.dumps(data).encode() if data else None
    req = urllib.request.Request(f"http://localhost:8081{path}", data=body, headers=headers, method=method)
    try:
        resp = urllib.request.urlopen(req, timeout=10)
        return json.loads(resp.read())
    except urllib.error.HTTPError as e:
        err = e.read().decode()
        print(f"  HTTP {e.code}: {err[:200]}")
        return None
    except Exception as e:
        print(f"  Error: {e}")
        return None

# Login
print("Logging in as vishnualgo@gmail.com...")
result = api("POST", "/api/auth/login", {"email": "vishnualgo@gmail.com", "password": "Temp@12345678"})
if not result or not result.get("accessToken"):
    print("Login failed!")
    sys.exit(1)

token = result["accessToken"]
print(f"  Token: {token[:30]}...")

# Place PAPER BUY
print("\n1. PAPER BUY RELIANCE qty=1:")
r = api("POST", "/api/orders/manual", {"symbol": "RELIANCE", "side": "BUY", "quantity": 1, "orderType": "MARKET", "mode": "PAPER"}, token)
if r: print(f"  {json.dumps(r, indent=2)}")

# Place PAPER BUY TCS
print("\n2. PAPER BUY TCS qty=2:")
r = api("POST", "/api/orders/manual", {"symbol": "TCS", "side": "BUY", "quantity": 2, "orderType": "MARKET", "mode": "PAPER"}, token)
if r: print(f"  {json.dumps(r, indent=2)}")

# Place PAPER SELL INFY
print("\n3. PAPER SELL INFY qty=1:")
r = api("POST", "/api/orders/manual", {"symbol": "INFY", "side": "SELL", "quantity": 1, "orderType": "MARKET", "mode": "PAPER"}, token)
if r: print(f"  {json.dumps(r, indent=2)}")
