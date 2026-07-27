import requests

BASE = "http://localhost:8081"

# Login
r = requests.post(f"{BASE}/api/auth/login", json={"email": "vishnualgo@gmail.com", "password": "`$ADMIN_PASSWORD"})
jwt = r.json().get("accessToken")
headers = {"Authorization": f"Bearer {jwt}"}

# Try different paths
paths = [
    "/api/brokers/zerodha/auto-reconnect/trigger",
    "/api/broker/zerodha/auto-reconnect/trigger",
    "/api/brokers/zerodha/token",
    "/api/zerodha/authenticate",
    "/api/zerodha/login-url",
    "/api/zerodha/set-token",
]
for p in paths:
    r = requests.post(f"{BASE}{p}", headers=headers, json={})
    print(f"POST {p}: {r.status_code} {r.text[:150]}")

# Also GET endpoints
for p in ["/api/brokers/health", "/api/zerodha/status"]:
    r = requests.get(f"{BASE}{p}", headers=headers)
    print(f"GET {p}: {r.status_code} {r.text[:200]}")

