import subprocess, json, urllib.request, urllib.error

p = subprocess.run(
    ['psql', '-h', 'localhost', '-U', 'postgres', '-d', 'stokr_lite', '-t', '-A', '-c',
     "SELECT id, client_id, left(access_token, 25) as tok, token_expiry, status FROM broker_accounts WHERE broker_name='ZERODHA' ORDER BY id DESC LIMIT 1"],
    capture_output=True, text=True, env={'PGPASSWORD': 'stokr2026'}
)
print(f"DB row: {p.stdout.strip()}")

p2 = subprocess.run(
    ['psql', '-h', 'localhost', '-U', 'postgres', '-d', 'stokr_lite', '-t', '-A', '-c',
     "SELECT access_token FROM broker_accounts WHERE broker_name='ZERODHA' ORDER BY id DESC LIMIT 1"],
    capture_output=True, text=True, env={'PGPASSWORD': 'stokr2026'}
)
access_token = p2.stdout.strip()
print(f"Token length: {len(access_token)}")

if not access_token:
    print("No token found!")
    exit(1)

req = urllib.request.Request(
    'https://api.kite.trade/user/profile',
    headers={'Authorization': f'token zazlrld244cc6jf0:{access_token}'}
)
try:
    resp = urllib.request.urlopen(req)
    data = json.loads(resp.read())
    user = data.get('data', {})
    print(f"User: {user.get('user_name', 'N/A')}")
    segments = user.get('segments', [])
    print(f"Segments: {segments}")
    if 'NFO' in segments:
        print(">>> NFO SEGMENT CONFIRMED ACTIVE <<<")
    else:
        print(">>> NFO NOT in segments <<<")
except urllib.error.HTTPError as e:
    body = e.read().decode()
    print(f"HTTP {e.code}: {body}")
except Exception as e:
    print(f"Error: {e}")
