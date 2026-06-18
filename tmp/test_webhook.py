import urllib.request, json

payload = json.dumps({
    "stocks": "RELIANCE,TCS,INFY",
    "trigger_prices": "2500.00,3600.00,1450.00",
    "triggered_at": "1:26 pm",
    "scan_name": "STOKR_MORNING_SURGE_SHORT",
    "scan_url": "stokr-morning-surge-short",
    "alert_name": "Alert for STOKR_MORNING_SURGE_SHORT",
    "webhook_url": "https://webhook.site/974aa66c-2913-4e7d-8dd3-1ec59c790a1f"
}).encode()

req = urllib.request.Request(
    "http://localhost:8070/webhooks/chartink/intraday",
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
