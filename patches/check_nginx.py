import json, subprocess
time.sleep(20)
r1 = subprocess.run(['curl', '-s', 'http://localhost:8080/api/option-arbitrage/scan?underlying=BANKNIFTY'], capture_output=True, text=True, timeout=90)
d1 = json.loads(r1.stdout) if r1.stdout.strip() else {"error": r1.stdout}
print("8080 keys:", list(d1.keys()))
print("8080 ceBid:", d1.get('opportunities', [{}])[0].get('ceBid', 'N/A') if d1.get('opportunities') else 'no opps')
