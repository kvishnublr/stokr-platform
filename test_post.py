import urllib.request, json

data = json.dumps({"opportunityId": 161208, "underlying": "NIFTY", "strike": 24400, "action": "BUY CE+PE / SELL FUT", "strategyType": "BID_PARITY", "lots": 1, "broker": "NAVIA"}).encode()
req = urllib.request.Request(
    "http://127.0.0.1:8081/api/option-arbitrage/paper-trade/execute",
    data=data,
    headers={"Content-Type": "application/json"},
    method="POST"
)
try:
    resp = urllib.request.urlopen(req)
    print(resp.read().decode())
except Exception as e:
    print(f"Error: {e}")
    if hasattr(e, 'read'):
        print(e.read().decode())
