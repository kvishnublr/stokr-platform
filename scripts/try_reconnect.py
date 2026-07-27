import requests, json

BASE = "http://localhost:8081"
users = [
    ("admin@stokr.in", "`$ADMIN_PASSWORD"),
    ("vishnualgo@gmail.com", "`$ADMIN_PASSWORD"),
    ("test@test.com", "`$ADMIN_PASSWORD"),
    ("kvishnu.blr@gmail.com", "`$ADMIN_PASSWORD"),
]

for email, pw in users:
    r = requests.post(f"{BASE}/api/auth/login", json={"email": email, "password": pw})
    print(f"{email}: {r.status_code}")
    if r.status_code == 200:
        data = r.json()
        jwt = data.get("accessToken") or data.get("token") or data.get("access_token")
        print(f"  JWT: {jwt[:40] if jwt else 'none'}...")
        # Trigger reconnect
        headers = {"Authorization": f"Bearer {jwt}"}
        r2 = requests.post(f"{BASE}/api/brokers/zerodha/auto-reconnect/trigger", headers=headers)
        print(f"  Trigger: {r2.status_code} {r2.text[:300]}")
        break
    else:
        print(f"  {r.text[:200]}")

