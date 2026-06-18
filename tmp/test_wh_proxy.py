import urllib.request, json

payload = json.dumps({
    "stocks": "RELIANCE,TCS,INFY",
    "trigger_prices": "2500.00,3600.00,1450.00",
    "triggered_at": "1:26 pm",
    "scan_name": "STOKR_MORNING_SURGE_SHORT",
    "scan_url": "stokr-morning-surge-short",
    "alert_name": "Alert for STOKR_MORNING_SURGE_SHORT",
    "webhook_url": "https://stokr.in/webhooks/chartink/intraday"
}).encode()

# Test via nginx proxy (port 8082)
req = urllib.request.Request(
    "http://localhost:8082/webhooks/chartink/intraday",
    data=payload,
    headers={"Content-Type": "application/json"}
)
try:
    resp = urllib.request.urlopen(req)
    print(f"Status: {resp.status}")
    print(resp.read().decode())
except urllib.error.HTTPError as e:
    print(f"Error: {e.code}")
    print(e.read().decode())
