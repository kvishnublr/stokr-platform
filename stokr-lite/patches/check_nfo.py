import json, subprocess, sys

# Get token from file or DB
try:
    token_data = json.load(open('/opt/stokr/stokr-platform/stokr-lite/data/zerodha_token.json'))
    access_token = token_data['access_token']
except:
    from subprocess import Popen, PIPE
    p = Popen(['psql', '-h', 'localhost', '-U', 'postgres', '-d', 'stokr_lite', '-t', '-A', '-c',
               "SELECT access_token FROM broker_accounts WHERE broker='ZERODHA' ORDER BY id DESC LIMIT 1"],
              stdin=PIPE, stdout=PIPE, stderr=PIPE, env={'PGPASSWORD': 'stokr2026'})
    access_token = p.stdout.read().decode().strip()

# Check margins and segments
import urllib.request
req = urllib.request.Request(
    'https://api.kite.trade/user/margins',
    headers={'Authorization': f'token zazlrld244cc6jf0:{access_token}'}
)
try:
    resp = urllib.request.urlopen(req)
    data = json.loads(resp.read())
    eq = data.get('data', {}).get('equity', {})
    com = data.get('data', {}).get('commodity', {})
    print(f"=== EQUITY MARGINS ===")
    print(f"  Available: {eq.get('available', {}).get('cash', 'N/A')}")
    print(f"  Used: {eq.get('used', {}).get('debits', 'N/A')}")
    print(f"  Total: {eq.get('total', {}).get('collateral', 'N/A')}")
    print(f"\n=== COMMODITY MARGINS ===")
    print(f"  Available: {com.get('available', {}).get('cash', 'N/A')}")
except Exception as e:
    print(f"Error: {e}")

# Check positions (should now accept NFO)
req2 = urllib.request.Request(
    'https://api.kite.trade/portfolio/positions',
    headers={'Authorization': f'token zazlrld244cc6jf0:{access_token}'}
)
try:
    resp2 = urllib.request.urlopen(req2)
    data2 = json.loads(resp2.read())
    net = data2.get('data', {}).get('net', [])
    print(f"\n=== POSITIONS ({len(net)} total) ===")
    for p in net:
        print(f"  {p.get('exchange','?'):4s} {p.get('tradingsymbol','?'):30s} {p.get('product','?'):4s} qty={p.get('quantity',0):>6d} avg={p.get('average_price',0):>10.2f}")
    if not net:
        print("  (no positions)")
except Exception as e:
    print(f"Positions error: {e}")
