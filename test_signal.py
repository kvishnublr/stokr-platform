import urllib.request
import json

payload = {
    "alertName": "STOKR_VWAP_TRIPLE_LONG",
    "symbol": "RELIANCE",
    "ltp": 2850.50,
    "volume": 150000,
    "buyerQty": 80000,
    "sellerQty": 40000,
    "changePct": 1.2,
    "vwapDeviationPct": 0.5,
    "atr14": 12.5,
    "adx14": 35.0,
    "rvol": 1.8,
    "open": 2820.0,
    "high": 2860.0,
    "low": 2815.0,
    "close": 2850.50,
    "prevClose": 2818.0,
    "bestBid": 2849.0,
    "bestAsk": 2851.0,
    "bidQty": 500,
    "askQty": 600,
    "niftyChangePct": 0.8,
    "stockCategory": "LARGE_CAP",
    "timestamp": "2025-06-17T10:30:00",
    "triggerType": "SCANNER"
}

req = urllib.request.Request(
    "http://localhost:8070/webhooks/chartink/intraday",
    data=json.dumps(payload).encode("utf-8"),
    headers={"Content-Type": "application/json"},
    method="POST"
)

try:
    with urllib.request.urlopen(req, timeout=15) as resp:
        print("Status:", resp.status)
        print("Response:", resp.read().decode("utf-8"))
except urllib.error.HTTPError as e:
    print("HTTP Error:", e.code)
    print("Body:", e.read().decode("utf-8"))
except Exception as e:
    print("Error:", str(e))
