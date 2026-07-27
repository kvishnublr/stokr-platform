import json, subprocess, os, urllib.request

env = os.environ.copy()
env["PGPASSWORD"] = "`$POSTGRES_PASSWORD"

def psql(sql):
    r = subprocess.run(["psql", "-h", "localhost", "-U", "postgres", "-d", "stokr_lite", "-t", "-A", "-c", sql],
        capture_output=True, text=True, env=env, timeout=10)
    return r.stdout.strip()

access_token = psql("SELECT access_token FROM broker_accounts WHERE id=4")
api_key = "`$ZERODHA_API_KEY"

# Zerodha format: token api_key:access_token
auth = f"token {api_key}:{access_token}"
print(f"Auth: {auth[:30]}...")

# Test RELIANCE (instrument token 738561)
url = "https://api.kite.trade/instruments/historical/738561/daily?from=2026-07-09&to=2026-07-13"
headers = {"Authorization": auth, "X-Kite-Version": "3"}
req = urllib.request.Request(url, headers=headers)
try:
    resp = urllib.request.urlopen(req, timeout=10)
    data = json.loads(resp.read())
    candles = data.get("data", {}).get("candles", [])
    print(f"RELIANCE: {len(candles)} candles")
    for c in candles:
        print(f"  {c[0]}: O={c[1]} H={c[2]} L={c[3]} C={c[4]} V={c[5]}")
except urllib.error.HTTPError as e:
    print(f"HTTP {e.code}: {e.read().decode()[:500]}")

