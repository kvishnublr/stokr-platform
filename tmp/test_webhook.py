import http.client, json

payload = json.dumps({
    "stocks": "RELIANCE,TCS",
    "trigger_prices": "2500.50,3500.00",
    "scan_name": "STOKR_VWAP_TRIPLE_LONG",
    "VWAP": "2498.00,3495.00",
    "RSI(14)": "65.4,58.2",
    "ATR(14)": "25.50,30.20",
    "Buyer Qty": "50000,30000",
    "Seller Qty": "25000,20000"
})

conn = http.client.HTTPConnection("127.0.0.1", 8070)
conn.request("POST", "/webhooks/chartink/intraday", body=payload, headers={"Content-Type": "application/json"})
resp = conn.getresponse()
data = json.loads(resp.read().decode())
print(json.dumps(data, indent=2))
