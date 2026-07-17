import subprocess, json

def ssh(cmd):
    r = subprocess.run(["ssh", "root@173.249.55.84", cmd], capture_output=True, text=True, timeout=60)
    return r.stdout.strip()

print("=== Verifying Deployment ===\n")

# 1. Health check
print("1. Health:")
h = ssh("curl -s 'http://localhost:8081/api/option-arbitrage/health'")
try:
    d = json.loads(h)
    print(f"   Status: {d.get('status')}, Underlyings: {d.get('supportedUnderlyings')}")
except:
    print(f"   {h[:100]}")

# 2. NIFTY scan
print("\n2. NIFTY scan:")
s = ssh("curl -s 'http://localhost:8081/api/option-arbitrage/scan?underlying=NIFTY'")
try:
    d = json.loads(s)
    print(f"   Status: {d.get('status')}, Opps: {d.get('totalOpportunities')}")
except:
    print(f"   {s[:100]}")

# 3. MIDCPNIFTY scan
print("\n3. MIDCPNIFTY scan:")
s = ssh("curl -s 'http://localhost:8081/api/option-arbitrage/scan?underlying=MIDCPNIFTY'")
try:
    d = json.loads(s)
    print(f"   Status: {d.get('status')}, Opps: {d.get('totalOpportunities')}")
except:
    print(f"   {s[:100]}")

# 4. FINNIFTY scan
print("\n4. FINNIFTY scan:")
s = ssh("curl -s 'http://localhost:8081/api/option-arbitrage/scan?underlying=FINNIFTY'")
try:
    d = json.loads(s)
    print(f"   Status: {d.get('status')}, Opps: {d.get('totalOpportunities')}")
except:
    print(f"   {s[:100]}")

# 5. ALL scan
print("\n5. ALL scan:")
s = ssh("curl -s 'http://localhost:8081/api/option-arbitrage/scan?underlying=ALL'")
try:
    d = json.loads(s)
    print(f"   Status: {d.get('status')}, Opps: {d.get('totalOpportunities')}")
    if d.get('opportunities'):
        for o in d['opportunities'][:3]:
            print(f"   {o.get('underlying')} {o.get('strike')} {o.get('type')} edge=₹{o.get('edgeAfterCosts', 0):.0f}")
except:
    print(f"   {s[:100]}")

# 6. Calendar spread
print("\n6. Calendar spread:")
c = ssh("curl -s 'http://localhost:8081/api/option-arbitrage/calendar-spread?underlying=NIFTY'")
try:
    d = json.loads(c)
    print(f"   Status: {d.get('status')}, Spreads: {d.get('totalSpreads')}")
except:
    print(f"   {c[:100]}")

# 7. Vol surface
print("\n7. Vol surface (NIFTY):")
v = ssh("curl -s 'http://localhost:8081/api/option-arbitrage/vol-surface?underlying=NIFTY'")
try:
    d = json.loads(v)
    s = d.get('summary', {})
    print(f"   Status: {d.get('status')}")
    print(f"   Weekly IV: {s.get('avgWeeklyIV')}%, Monthly IV: {s.get('avgMonthlyIV')}%")
    print(f"   Vol Signal: {s.get('volSignal')}, Skew: {s.get('skewSignal')}, Term: {s.get('termSignal')}")
    if d.get('surface'):
        print(f"   Surface: {len(d['surface'])} strikes")
except:
    print(f"   {v[:100]}")

# 8. Check for errors
print("\n8. Recent errors:")
errs = ssh("docker logs stokr-lite-backend 2>&1 | grep -i 'ERROR\|overflow' | tail -3")
print(f"   {errs or 'None'}")
