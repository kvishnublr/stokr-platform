import subprocess, json, urllib.request, urllib.error

p2 = subprocess.run(
    ['psql', '-h', 'localhost', '-U', 'postgres', '-d', 'stokr_lite', '-t', '-A', '-c',
     "SELECT access_token FROM broker_accounts WHERE broker_name='ZERODHA' ORDER BY id DESC LIMIT 1"],
    capture_output=True, text=True, env={'PGPASSWORD': 'stokr2026'}
)
access_token = p2.stdout.strip()

# Check margins
req = urllib.request.Request(
    'https://api.kite.trade/user/margins',
    headers={'Authorization': f'token zazlrld244cc6jf0:{access_token}'}
)
try:
    resp = urllib.request.urlopen(req)
    data = json.loads(resp.read())
    eq = data.get('data', {}).get('equity', {})
    print("=== EQUITY MARGINS ===")
    print(json.dumps(eq, indent=2)[:500])
    
    # NFO margins might be under equity or separate
    print("\n=== ALL KEYS ===")
    print(list(data.get('data', {}).keys()))
except urllib.error.HTTPError as e:
    body = e.read().decode()
    print(f"HTTP {e.code}: {body}")
except Exception as e:
    print(f"Error: {e}")

# Test NFO order with a dummy request to check segment
# Try fetching NFO instrument info
print("\n=== NFO INSTRUMENT CHECK ===")
req3 = urllib.request.Request(
    'https://api.kite.trade/instruments/search?i=NIFTY26JULFUT',
    headers={'Authorization': f'token zazlrld244cc6jf0:{access_token}'}
)
try:
    resp3 = urllib.request.urlopen(req3)
    data3 = json.loads(resp3.read())
    instruments = data3.get('data', [])
    print(f"Found {len(instruments)} instruments")
    for inst in instruments[:3]:
        print(f"  {inst.get('tradingsymbol', '?')} exchange={inst.get('exchange', '?')} instrument_type={inst.get('instrument_type', '?')}")
except urllib.error.HTTPError as e:
    body = e.read().decode()
    print(f"HTTP {e.code}: {body[:300]}")
except Exception as e:
    print(f"Error: {e}")
