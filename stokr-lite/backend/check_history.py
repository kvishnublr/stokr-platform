import urllib.request, json

# Test NIFTY with date
url = "http://127.0.0.1:8081/api/option-arbitrage/history?size=5&strategyType=BID_PARITY&startDate=2026-08-05&endDate=2026-08-05&underlying=NIFTY"
with urllib.request.urlopen(url) as resp:
    d = json.loads(resp.read())
print("NIFTY totalElements:", d['totalElements'], "count:", d['count'])
for i in d['items']:
    print(f"  {i['underlying']} edge={i['edgeAfterCosts']} time={i['scanTime'][:19]}")

# Test ALL (no underlying param)
url2 = "http://127.0.0.1:8081/api/option-arbitrage/history?size=5&strategyType=BID_PARITY&startDate=2026-08-05&endDate=2026-08-05"
with urllib.request.urlopen(url2) as resp2:
    d2 = json.loads(resp2.read())
print("\nALL (no underlying) totalElements:", d2['totalElements'], "count:", d2['count'])
for i in d2['items']:
    print(f"  {i['underlying']} edge={i['edgeAfterCosts']} time={i['scanTime'][:19]}")

# Test BOX with date
url3 = "http://127.0.0.1:8081/api/option-arbitrage/history?size=5&strategyType=BOX_SPREAD&startDate=2026-08-05&endDate=2026-08-05"
with urllib.request.urlopen(url3) as resp3:
    d3 = json.loads(resp3.read())
print("\nBOX ALL totalElements:", d3['totalElements'], "count:", d3['count'])
for i in d3['items']:
    print(f"  {i['underlying']} edge={i['edgeAfterCosts']} time={i['scanTime'][:19]}")
