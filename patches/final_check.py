import json, subprocess, time
time.sleep(65)

r = subprocess.run(['curl', '-sk', '--max-time', '90', 'https://localhost/api/option-arbitrage/scan?underlying=BANKNIFTY'], capture_output=True, text=True, timeout=120)
d = json.loads(r.stdout) if r.stdout.strip() else {"error": "empty"}
print(f"Total: {d.get('count', len(d.get('opportunities', [])))}")
print(f"Keys: {list(d.keys())}")
for o in d.get('opportunities', [])[:3]:
    print(f"  {o.get('type')} {o.get('strike')} ceBid={o.get('ceBid',0)} ceAsk={o.get('ceAsk',0)} peBid={o.get('peBid',0)} peAsk={o.get('peAsk',0)} edge={o.get('edgeAfterCosts',0)}")
