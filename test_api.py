import json, subprocess, os, urllib.request

env = os.environ.copy()
env["PGPASSWORD"] = "stokr2026"

def psql(sql):
    r = subprocess.run(["psql", "-h", "localhost", "-U", "postgres", "-d", "stokr_lite", "-t", "-A", "-c", sql],
        capture_output=True, text=True, env=env, timeout=10)
    return r.stdout.strip()

token = psql("SELECT access_token FROM broker_accounts WHERE id=4")

# Test the API with one symbol
url = "https://api.kite.trade/instruments/historical/738561/daily?from=2026-07-09&to=2026-07-13"
headers = {"Authorization": f"Token {token}", "X-Kite-Version": "3"}
req = urllib.request.Request(url, headers=headers)
try:
    resp = urllib.request.urlopen(req, timeout=10)
    print(resp.read().decode()[:500])
except urllib.error.HTTPError as e:
    print(f"HTTP {e.code}: {e.read().decode()[:500]}")
