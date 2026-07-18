import json, subprocess, time
time.sleep(35)
r1 = subprocess.run(['curl', '-s', 'http://localhost:8080/api/option-arbitrage/scan?underlying=BANKNIFTY'], capture_output=True, text=True, timeout=60)
r2 = subprocess.run(['curl', '-s', 'http://localhost:8081/api/option-arbitrage/scan?underlying=BANKNIFTY'], capture_output=True, text=True, timeout=60)

d1 = json.loads(r1.stdout) if r1.stdout.strip() else {"error": "empty"}
d2 = json.loads(r2.stdout) if r2.stdout.strip() else {"error": "empty"}

print("8080 (nginx):", list(d1.keys()))
print("8081 (direct):", list(d2.keys()))
if d1.get('opportunities'):
    o = d1['opportunities'][0]
    print("8080 first opp:", o.get('type'), o.get('ceBid'), o.get('peBid'))
if d2.get('opportunities'):
    o = d2['opportunities'][0]
    print("8081 first opp:", o.get('type'), o.get('ceBid'), o.get('peBid'))
