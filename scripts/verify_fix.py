import json, urllib.request, math

# Test 1: Direct scan
data = json.loads(urllib.request.urlopen("http://localhost:8081/api/option-arbitrage/scan?underlying=NIFTY").read())
opps = data.get("opportunities", [])
total = data.get("totalOpportunities", 0)

print("=== Fix Verification ===")
print(f"NIFTY scan: {total} opportunities")

# Test 2: Cached
data2 = json.loads(urllib.request.urlopen("http://localhost:8081/api/option-arbitrage/opportunities?underlying=BOTH").read())
print(f"Cached (BOTH): {data2.get('totalOpportunities', 0)} opportunities")

# Test 3: Check the actual parity deviation with corrected futures
# Fetch spot to compute expected
url = "http://localhost:8081/api/option-arbitrage/health"
health = json.loads(urllib.request.urlopen(url).read())
print(f"Health: {json.dumps(health.get('settings', {}))}")

# Compute what the synthetic forward should be
spot_est = 24193  # from earlier
fut_actual = 24024.2  # stale quote
r = 0.065
dte = 6
premium_est = spot_est * (math.exp(r * 7.0 / 365.0) - 1.0)
low = spot_est - premium_est * 3
high = spot_est + premium_est * 5

print(f"\nSpot estimate: {spot_est}")
print(f"Futures stale: {fut_actual}")
print(f"Premium estimate (7d): {premium_est:.1f}")
print(f"Expected range: [{low:.1f}, {high:.1f}]")
print(f"Futures in range: {low <= fut_actual <= high}")
print(f"Synthetic forward: {spot_est * math.exp(r * 7.0 / 365.0):.1f}")

if total == 0:
    print("\n✓ PASS: Stale futures caught, no phantom parity breaks")
else:
    print(f"\n✗ FAIL: Still showing {total} opportunities")

# Check recent overflow errors
import subprocess
result = subprocess.run(
    ["ssh", "root@173.249.55.84",
     "docker logs stokr-lite-backend 2>&1 | grep 'overflow' | tail -3"],
    capture_output=True, text=True, timeout=15
)
if result.stdout.strip():
    print(f"\n✗ Still getting overflow errors:\n{result.stdout.strip()}")
else:
    print("✓ No overflow errors in recent logs")
