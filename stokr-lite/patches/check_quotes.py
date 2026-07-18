import subprocess, json, urllib.request, urllib.error
from urllib.parse import quote

def get_token():
    p = subprocess.run(
        ['psql', '-h', 'localhost', '-U', 'postgres', '-d', 'stokr_lite', '-t', '-A', '-c',
         "SELECT access_token FROM broker_accounts WHERE broker_name='ZERODHA' ORDER BY id DESC LIMIT 1"],
        capture_output=True, text=True, env={'PGPASSWORD': 'stokr2026'}
    )
    return p.stdout.strip()

TOKEN = get_token()
HEADERS = {'Authorization': f'token zazlrld244cc6jf0:{TOKEN}', 'X-Kite-Version': '3'}

def api_quote(instruments):
    parts = [f'i={quote(inst)}' for inst in instruments]
    url = f'https://api.kite.trade/quote?{"&".join(parts)}'
    req = urllib.request.Request(url, headers=HEADERS)
    resp = urllib.request.urlopen(req)
    return json.loads(resp.read())

# Check a few MIDCPNIFTY and FINNIFTY options
instruments = [
    'NFO:MIDCPNIFTY26JUL14700CE', 'NFO:MIDCPNIFTY26JUL14700PE',
    'NFO:MIDCPNIFTY26JUL14750CE', 'NFO:MIDCPNIFTY26JUL14750PE',
    'NFO:FINNIFTY26JUL26900CE', 'NFO:FINNIFTY26JUL26900PE',
    'NFO:FINNIFTY26JUL26950CE', 'NFO:FINNIFTY26JUL26950PE',
    'NFO:BANKNIFTY26JUL58000CE', 'NFO:BANKNIFTY26JUL58000PE',
]

resp = api_quote(instruments)
data = resp.get('data', {})

for inst in instruments:
    q = data.get(inst, {})
    if not q:
        print(f'{inst}: NOT FOUND')
        continue
    last = q.get('last_price', 0)
    depth = q.get('depth', {})
    buy = depth.get('buy', [])
    sell = depth.get('sell', [])
    bid = buy[0]['price'] if buy else 0
    ask = sell[0]['price'] if sell else 0
    vol = q.get('volume', 0)
    oi = q.get('open_interest', 0)
    spread = ask - bid if bid > 0 and ask > 0 else 0
    print(f'{inst}: last={last:.2f} bid={bid:.2f} ask={ask:.2f} spread={spread:.2f} vol={vol} oi={oi} buy_depth={len(buy)} sell_depth={len(sell)}')
