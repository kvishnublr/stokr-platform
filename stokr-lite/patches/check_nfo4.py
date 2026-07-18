import subprocess, json, urllib.request, urllib.error

p2 = subprocess.run(
    ['psql', '-h', 'localhost', '-U', 'postgres', '-d', 'stokr_lite', '-t', '-A', '-c',
     "SELECT access_token FROM broker_accounts WHERE broker_name='ZERODHA' ORDER BY id DESC LIMIT 1"],
    capture_output=True, text=True, env={'PGPASSWORD': 'stokr2026'}
)
access_token = p2.stdout.strip()

# Check margins (full)
req = urllib.request.Request(
    'https://api.kite.trade/user/margins',
    headers={'Authorization': f'token zazlrld244cc6jf0:{access_token}'}
)
try:
    resp = urllib.request.urlopen(req)
    data = json.loads(resp.read())
    d = data.get('data', {})
    print("Margin segments:", list(d.keys()))
    for seg_name, seg_data in d.items():
        print(f"\n=== {seg_name.upper()} ===")
        print(f"  enabled: {seg_data.get('enabled')}")
        print(f"  net: {seg_data.get('net')}")
        avail = seg_data.get('available', {})
        print(f"  cash: {avail.get('cash')}")
        print(f"  live_balance: {avail.get('live_balance')}")
        util = seg_data.get('utilised', {})
        print(f"  span: {util.get('span')}")
        print(f"  exposure: {util.get('exposure')}")
except urllib.error.HTTPError as e:
    body = e.read().decode()
    print(f"HTTP {e.code}: {body[:500]}")

# Check orders via API
print("\n=== RECENT ORDERS ===")
req2 = urllib.request.Request(
    'https://api.kite.trade/orders',
    headers={'Authorization': f'token zazlrld244cc6jf0:{access_token}'}
)
try:
    resp2 = urllib.request.urlopen(req2)
    data2 = json.loads(resp2.read())
    orders = data2.get('data', [])
    for o in orders[-5:]:
        print(f"  {o.get('exchange','?'):4s} {o.get('tradingsymbol','?'):30s} {o.get('transaction_type','?'):4s} {o.get('product','?'):4s} qty={o.get('quantity',0)} status={o.get('status','?')}")
except urllib.error.HTTPError as e:
    body = e.read().decode()
    print(f"HTTP {e.code}: {body[:500]}")
