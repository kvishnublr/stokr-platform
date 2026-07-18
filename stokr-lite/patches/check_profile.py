import json, urllib.request, urllib.error

# Read token from DB
import subprocess
p = subprocess.run(
    ['psql', '-h', 'localhost', '-U', 'postgres', '-d', 'stokr_lite', '-t', '-A', '-c',
     "SELECT access_token FROM broker_accounts WHERE broker='ZERODHA' ORDER BY id DESC LIMIT 1"],
    capture_output=True, text=True, env={'PGPASSWORD': 'stokr2026'}
)
access_token = p.stdout.strip()
print(f"Token from DB: {access_token[:20]}..." if access_token else "No token in DB")

if not access_token:
    print("No token found!")
    exit(1)

# Profile request
req = urllib.request.Request(
    'https://api.kite.trade/user/profile',
    headers={'Authorization': f'token zazlrld244cc6jf0:{access_token}'}
)
try:
    resp = urllib.request.urlopen(req)
    data = json.loads(resp.read())
    user = data.get('data', {})
    print(f"User: {user.get('user_name', 'N/A')}")
    print(f"Broker: {user.get('broker', 'N/A')}")
    segments = user.get('segments', [])
    print(f"Segments: {segments}")
    products = user.get('products', [])
    print(f"Products: {products}")
    if 'NFO' in segments or 'FNO' in segments:
        print(">>> NFO SEGMENT CONFIRMED ACTIVE <<<")
    else:
        print(">>> NFO NOT in segments list <<<")
except urllib.error.HTTPError as e:
    body = e.read().decode()
    print(f"HTTP {e.code}: {body}")
except Exception as e:
    print(f"Error: {e}")
