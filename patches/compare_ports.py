import json, subprocess

subprocess.run(['curl', '-s', 'http://localhost:8081/api/option-arbitrage/scan?underlying=BANKNIFTY'], stdout=open('/tmp/r1.json','w'), timeout=60)
subprocess.run(['curl', '-s', 'http://localhost:8080/api/option-arbitrage/scan?underlying=BANKNIFTY'], stdout=open('/tmp/r2.json','w'), timeout=60)

d1 = json.load(open('/tmp/r1.json'))
d2 = json.load(open('/tmp/r2.json'))

print("8081 keys:", list(d1.keys()))
print("8080 keys:", list(d2.keys()))
print("8081 count:", d1.get('count', 'N/A'))
print("8080 totalOpportunities:", d2.get('totalOpportunities', 'N/A'))
print("8080 status:", d2.get('status', 'N/A'))
