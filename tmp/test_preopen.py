import urllib.request, json

payload = json.dumps({
    "stocks": "RELIANCE,TCS,SBIN",
    "trigger_prices": "2600.00,3700.00,870.00",
    "triggered_at": "9:07 am",
    "scan_name": "STOKR_PRE_OPEN_BUY",
    "scan_url": "stokr-pre-open-buy",
    "alert_name": "Alert for STOKR_PRE_OPEN_BUY",
    "webhook_url": "https://stokr.in/webhooks/chartink/preopen"
}).encode()

req = urllib.request.Request(
    "http://localhost:8070/webhooks/chartink/preopen",
    data=payload,
    headers={"Content-Type": "application/json"}
)
resp = urllib.request.urlopen(req)
print("Via backend (8070):")
print(resp.read().decode())

# Also test via nginx proxy
req2 = urllib.request.Request(
    "http://localhost:8082/webhooks/chartink/preopen",
    data=payload,
    headers={"Content-Type": "application/json"}
)
resp2 = urllib.request.urlopen(req2)
print("\nVia nginx (8082):")
print(resp2.read().decode())
