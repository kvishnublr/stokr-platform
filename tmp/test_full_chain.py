import urllib.request, json, sys

# Test ALL 5 endpoints with their respective scanner names
tests = [
    ("intraday", {"stocks":"RELIANCE,TCS,INFY","trigger_prices":"2500.00,3600.00,1450.00","triggered_at":"1:26 pm","scan_name":"STOKR_VWAP_TRIPLE_LONG","scan_url":"stokr-vwap-triple-long","alert_name":"Alert for STOKR_VWAP_TRIPLE_LONG","webhook_url":"https://stokr.in/webhooks/chartink/intraday"}),
    ("intraday", {"stocks":"SBIN,ICICIBANK","trigger_prices":"850.00,1250.00","triggered_at":"1:27 pm","scan_name":"STOKR_TRADE_BOOK_IMBALANCE","scan_url":"stokr-trade-book-imbalance","alert_name":"Alert for STOKR_TRADE_BOOK_IMBALANCE","webhook_url":"https://stokr.in/webhooks/chartink/intraday"}),
    ("intraday", {"stocks":"HDFC,BHARTIARTL","trigger_prices":"1650.00,1450.00","triggered_at":"1:28 pm","scan_name":"STOKR_ORB_V_BREAKOUT","scan_url":"stokr-orb-v-breakout","alert_name":"Alert for STOKR_ORB_V_BREAKOUT","webhook_url":"https://stokr.in/webhooks/chartink/intraday"}),
    ("intraday", {"stocks":"RELIANCE,TCS","trigger_prices":"2480.00,3580.00","triggered_at":"1:29 pm","scan_name":"STOKR_MORNING_SURGE_SHORT","scan_url":"stokr-morning-surge-short","alert_name":"Alert for STOKR_MORNING_SURGE_SHORT","webhook_url":"https://stokr.in/webhooks/chartink/intraday"}),
    ("preopen", {"stocks":"RELIANCE,TCS,INFY,SBIN,HDFC","trigger_prices":"2520.00,3620.00,1460.00,860.00,1670.00","triggered_at":"9:09 am","scan_name":"STOKR_PRE_OPEN_BUY","scan_url":"stokr-pre-open-buy","alert_name":"Alert for STOKR_PRE_OPEN_BUY","webhook_url":"https://stokr.in/webhooks/chartink/preopen"}),
]

all_pass = True
for endpoint, payload in tests:
    url = f"http://localhost:8070/webhooks/chartink/{endpoint}"
    data = json.dumps(payload).encode()
    req = urllib.request.Request(url, data=data, headers={"Content-Type": "application/json"})
    try:
        resp = urllib.request.urlopen(req)
        body = json.loads(resp.read())
        print(f"[{resp.status}] /webhooks/chartink/{endpoint} ({payload['scan_name']})")
        for r in body.get("results", []):
            print(f"  -> {r.get('symbol','?'):20s} {r.get('reason','?')}")
    except Exception as e:
        print(f"[FAIL] /webhooks/chartink/{endpoint}: {e}")
        all_pass = False
    print()

sys.exit(0 if all_pass else 1)
