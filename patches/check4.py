import json, subprocess
r = subprocess.run(['curl', '-s', 'http://localhost:8080/api/option-arbitrage/scan?underlying=BANKNIFTY'], capture_output=True, text=True, timeout=60)
d = json.loads(r.stdout)
print("Response keys:", list(d.keys()))
if d.get('opportunities'):
    print("First opp keys:", list(d['opportunities'][0].keys()))
    print("ceBid:", d['opportunities'][0].get('ceBid'))
    print("ceAsk:", d['opportunities'][0].get('ceAsk'))
