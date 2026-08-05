import urllib.request, json

# Test 1: Execute trade
data = json.dumps({"opportunityId": 161200, "underlying": "NIFTY", "strike": 24300, "action": "BUY CE+PE / SELL FUT", "strategyType": "BID_PARITY", "lots": 1, "broker": "NAVIA"}).encode()
req = urllib.request.Request("http://127.0.0.1:8081/api/option-arbitrage/paper-trade/execute", data=data, headers={"Content-Type": "application/json"}, method="POST")
try:
    print("TRADE TEST:", urllib.request.urlopen(req).read().decode())
except Exception as e:
    print("TRADE ERROR:", e)
    if hasattr(e, 'read'): print(e.read().decode())

# Test 2: Save settings
req2 = urllib.request.Request("http://127.0.0.1:8081/api/option-arbitrage/auto-execute/settings?key=enabled&value=true", method="POST")
req2.add_header("Content-Type", "application/json")
try:
    print("SETTINGS TEST:", urllib.request.urlopen(req2).read().decode())
except Exception as e:
    print("SETTINGS ERROR:", e)
    if hasattr(e, 'read'): print(e.read().decode())

# Test 3: Check live-positions
req3 = urllib.request.Request("http://127.0.0.1:8081/api/option-arbitrage/live-positions")
try:
    print("POSITIONS TEST:", urllib.request.urlopen(req3).read().decode()[:300])
except Exception as e:
    print("POSITIONS ERROR:", e)
    if hasattr(e, 'read'): print(e.read().decode())
