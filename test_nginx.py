import urllib.request, json

# Test through nginx (https)
data = json.dumps({"opportunityId": 161200}).encode()
req = urllib.request.Request("https://stokr.in/api/option-arbitrage/paper-trade/execute", data=data, headers={"Content-Type": "application/json"}, method="POST")
try:
    import ssl
    ctx = ssl.create_default_context()
    print("HTTPS TEST:", urllib.request.urlopen(req, context=ctx).read().decode())
except Exception as e:
    print("HTTPS ERROR:", e)
    if hasattr(e, 'read'): print(e.read().decode())
