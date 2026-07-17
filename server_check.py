import requests, json

r = requests.get("http://localhost:8081/api/strategies")
data = r.json()
for s in data:
    print(f"ID={s['id']} TYPE={s['strategyType']} NAME={s['name'][:50]} ENABLED={s['enabled']}")
print(f"\nTotal: {len(data)}")

# Check frontend built JS
import subprocess
r2 = subprocess.run(
    "docker exec stokr-lite-frontend sh -c 'grep -l INSIDER /usr/share/nginx/html/assets/*.js 2>/dev/null || echo NONE'",
    shell=True, capture_output=True, text=True)
print("\nInsider in frontend build:", r2.stdout.strip())

r3 = subprocess.run(
    "docker exec stokr-lite-frontend sh -c 'grep -c PROFITABLE_STRATEGIES /usr/share/nginx/html/assets/Strategies*.js 2>/dev/null || echo 0'",
    shell=True, capture_output=True, text=True)
print("Profitable_strategies files:", r3.stdout.strip())
