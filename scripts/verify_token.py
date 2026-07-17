import requests
BASE = "http://localhost:8081"
r = requests.get(f"{BASE}/api/brokers/health")
print("Health:", r.json())
r2 = requests.get(f"{BASE}/api/market/ltp/batch", params={"symbols": "RELIANCE,TCS,INFY"})
print("LTP:", r2.status_code, r2.text[:300])
