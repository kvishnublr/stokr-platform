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
        print(f"  HTTP {e.code}: {err[:300]}")
        return None
    except Exception as e:
        print(f"  Error: {e}")
        return None

print("Logging in...")
result = api("POST", "/api/auth/login", {"email": "vishnualgo@gmail.com", "password": "Temp@12345678"})
if not result or not result.get("accessToken"):
    print("Login failed!")
    sys.exit(1)
token = result["accessToken"]

print("\n1. List deployments:")
deps = api("GET", "/api/deployments", token=token)
if deps:
    for d in deps:
        print(f"  #{d['id']} {d.get('strategyName','?')} mode={d['mode']} capital={d['capital']} status={d['status']}")

if deps:
    dep_id = deps[0]['id']
    print(f"\n2. Edit deployment #{dep_id} - change capital to ₹50,000:")
    r = api("PATCH", f"/api/deployments/{dep_id}", {"capital": 50000}, token=token)
    if r: print(f"  Result: capital={r.get('capital')} mode={r.get('mode')}")

    print(f"\n3. Edit deployment #{dep_id} - switch to LIVE mode:")
    r = api("PATCH", f"/api/deployments/{dep_id}", {"mode": "LIVE"}, token=token)
    if r: print(f"  Result: capital={r.get('capital')} mode={r.get('mode')}")

    print(f"\n4. Edit deployment #{dep_id} - switch back to PAPER:")
    r = api("PATCH", f"/api/deployments/{dep_id}", {"mode": "PAPER"}, token=token)
    if r: print(f"  Result: capital={r.get('capital')} mode={r.get('mode')}")
