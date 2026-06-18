import urllib.request, json

BASE = 'http://173.249.55.84:8082'
login = json.dumps({'email':'trader@stokr.in','password':'Trader@123'}).encode()
resp = urllib.request.urlopen(urllib.request.Request(f'{BASE}/api/auth/login', data=login, headers={'Content-Type':'application/json'}), timeout=5)
token = json.loads(resp.read().decode()).get('accessToken','')
headers = {'Authorization': f'Bearer {token}', 'Content-Type': 'application/json'}

print("=== STRATEGIES ===")
req = urllib.request.Request(f'{BASE}/api/strategies', headers=headers)
resp = urllib.request.urlopen(req, timeout=5)
strats = json.loads(resp.read().decode())
print('Count:', len(strats))
for s in strats:
    print(' ', s['id'], s['name'], s.get('strategyType'), 'enabled='+str(s.get('enabled')))

print("\n=== DEPLOY PAPER MODE ===")
deploy_payload = json.dumps({'strategyId': strats[0]['id'], 'mode': 'PAPER', 'capital': 15000}).encode()
req = urllib.request.Request(f'{BASE}/api/deployments', data=deploy_payload, headers=headers)
try:
    resp = urllib.request.urlopen(req, timeout=5)
    print('OK:', resp.read().decode()[:300])
except urllib.error.HTTPError as e:
    print('ERR:', e.code, e.read().decode()[:300])

print("\n=== TRADER CONFIG ===")
req = urllib.request.Request(f'{BASE}/api/chartink/trader-config/1', headers=headers)
resp = urllib.request.urlopen(req, timeout=5)
cfg = json.loads(resp.read().decode())
print(json.dumps(cfg, indent=2))

print("\n=== POSITIONS ENDPOINT ===")
req = urllib.request.Request(f'{BASE}/api/chartink/positions', headers=headers)
try:
    resp = urllib.request.urlopen(req, timeout=5)
    body = resp.read().decode()[:200]
    print('OK:', body)
except urllib.error.HTTPError as e:
    print('ERR:', e.code, e.read().decode()[:200])

print("\n=== SIGNALS (last 3) ===")
req = urllib.request.Request(f'{BASE}/api/signals', headers=headers)
resp = urllib.request.urlopen(req, timeout=5)
sigs = json.loads(resp.read().decode())
print('Total signals:', len(sigs))
for s in sigs[-3:]:
    print(' ', s.get('symbol'), s.get('side'), s.get('status'), s.get('reason','')[:40])
