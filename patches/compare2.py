import json, subprocess

subprocess.run(['curl', '-s', 'http://localhost:8080/api/option-arbitrage/scan?underlying=BANKNIFTY'], stdout=open('/tmp/r_nginx.json','w'), timeout=60)
subprocess.run(['curl', '-s', 'http://localhost:8081/api/option-arbitrage/scan?underlying=BANKNIFTY'], stdout=open('/tmp/r_direct.json','w'), timeout=60)

d1 = json.load(open('/tmp/r_nginx.json'))
d2 = json.load(open('/tmp/r_direct.json'))

print("8080 (nginx):", list(d1.keys()))
print("8081 (direct):", list(d2.keys()))

if d1.get('opportunities'):
    o = d1['opportunities'][0]
    print("8080 first opp ceBid:", o.get('ceBid', 'MISSING'), "peBid:", o.get('peBid', 'MISSING'))

if d2.get('opportunities'):
    o = d2['opportunities'][0]
    print("8081 first opp ceBid:", o.get('ceBid', 'MISSING'), "peBid:", o.get('peBid', 'MISSING'))
