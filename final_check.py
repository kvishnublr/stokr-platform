import urllib.request, json

BASE = 'http://173.249.55.84:8082'
login = json.dumps({'email':'trader@stokr.in','password':'Trader@123'}).encode()
resp = urllib.request.urlopen(urllib.request.Request(f'{BASE}/api/auth/login', data=login, headers={'Content-Type':'application/json'}), timeout=5)
data = json.loads(resp.read().decode())
token = data.get('accessToken','')
uid = data.get('userId','?')
headers = {'Authorization': f'Bearer {token}', 'Content-Type': 'application/json'}
print('Login OK  userId=' + str(uid))

# Trader config by userId
req = urllib.request.Request(f'{BASE}/api/chartink/trader-config/{uid}', headers=headers)
try:
    resp = urllib.request.urlopen(req, timeout=5)
    cfg = json.loads(resp.read().decode())
    print('TraderConfig: userId=%s mode=%s capital=%s maxPositions=%s' % (cfg.get('userId'), cfg.get('mode'), cfg.get('capital'), cfg.get('maxPositions')))
except urllib.error.HTTPError as e:
    print('TraderConfig ERR: %d %s' % (e.code, e.read().decode()[:100]))

# Deploy PAPER
deploy_payload = json.dumps({'strategyId': 1, 'mode': 'PAPER', 'capital': 15000}).encode()
req = urllib.request.Request(f'{BASE}/api/deployments', data=deploy_payload, headers=headers)
try:
    resp = urllib.request.urlopen(req, timeout=5)
    d = json.loads(resp.read().decode())
    print('Deploy PAPER: id=%s mode=%s status=%s' % (d.get('id'), d.get('mode'), d.get('status')))
except urllib.error.HTTPError as e:
    print('Deploy ERR: %d %s' % (e.code, e.read().decode()[:200]))

# List deployments
req = urllib.request.Request(f'{BASE}/api/deployments', headers=headers)
resp = urllib.request.urlopen(req, timeout=5)
deps = json.loads(resp.read().decode())
print('Deployments: count=%d' % len(deps))

# Webhook test
wh_payload = json.dumps({
    'scannerName': 'STOKR_ORB_V_BREAKOUT', 'symbol': 'TCS', 'ltp': 4200.0,
    'volume': 80000, 'buyerQty': 50000, 'sellerQty': 30000, 'changePct': 0.8,
    'vwapDeviationPct': 0.1, 'atr14': 22.0, 'adx14': 30.0, 'rvol': 1.5,
    'open': 4180.0, 'high': 4210.0, 'low': 4175.0, 'close': 4198.0,
    'prevClose': 4165.0, 'bestBid': 4199.0, 'bestAsk': 4201.0,
    'bidQty': 2000, 'askQty': 1800, 'niftyChangePct': 0.3,
    'stockCategory': 'LARGECAP', 'timestamp': '2026-06-18T09:45:00Z', 'triggerType': 'SCANNER_HIT'
}).encode()
req = urllib.request.Request(f'{BASE}/webhooks/chartink/intraday', data=wh_payload, headers={'Content-Type': 'application/json'})
try:
    resp = urllib.request.urlopen(req, timeout=10)
    wh = json.loads(resp.read().decode())
    print('Webhook TCS: success=%s reason=%s' % (wh.get('success'), wh.get('reason', 'EXECUTED')))
except urllib.error.HTTPError as e:
    print('Webhook ERR: %d' % e.code)

print('ALL CHECKS DONE')
