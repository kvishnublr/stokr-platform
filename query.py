import urllib.request, json
data = json.loads(urllib.request.urlopen("http://localhost:8081/api/option-arbitrage/live-prices-batch?underlying=NIFTY").read())
prices = data.get('prices', {})
for k, v in list(prices.items())[:3]:
    print(f"ID {k}: ceLive={v.get('ceLive')}, peLive={v.get('peLive')}, spotLive={v.get('spotLive')}, futLive={v.get('futLive')}")
