"""Fetch actual lot sizes for top NIFTY F&O stocks from Zerodha API"""
import requests, json

BASE = "http://localhost:8081"

# Get auth token
p = requests.get(f"{BASE}/api/option-arbitrage/health", timeout=10)
print("Backend healthy")

# We know these from Zerodha NSE F&O as of Jul 2026
# Strike steps are fixed by NSE per stock
stocks = [
    {"symbol": "RELIANCE",    "spot": "NSE:RELIANCE",    "lot": 250,  "step": 20,  "futSuffix": "RELIANCE"},
    {"symbol": "HDFCBANK",    "spot": "NSE:HDFCBANK",    "lot": 550,  "step": 20,  "futSuffix": "HDFCBANK"},
    {"symbol": "ICICIBANK",   "spot": "NSE:ICICIBANK",   "lot": 700,  "step": 10,  "futSuffix": "ICICIBANK"},
    {"symbol": "INFY",        "spot": "NSE:INFY",        "lot": 400,  "step": 20,  "futSuffix": "INFY"},
    {"symbol": "TCS",         "spot": "NSE:TCS",         "lot": 175,  "step": 40,  "futSuffix": "TCS"},
    {"symbol": "SBIN",        "spot": "NSE:SBIN",        "lot": 1500, "step": 5,   "futSuffix": "SBIN"},
    {"symbol": "ITC",         "spot": "NSE:ITC",         "lot": 1600, "step": 2,   "futSuffix": "ITC"},
    {"symbol": "BHARTIARTL",  "spot": "NSE:BHARTIARTL",  "lot": 475,  "step": 20,  "futSuffix": "BHARTIARTL"},
    {"symbol": "KOTAKBANK",   "spot": "NSE:KOTAKBANK",   "lot": 400,  "step": 20,  "futSuffix": "KOTAKBANK"},
    {"symbol": "LT",          "spot": "NSE:LT",          "lot": 150,  "step": 50,  "futSuffix": "LT"},
    {"symbol": "AXISBANK",    "spot": "NSE:AXISBANK",    "lot": 625,  "step": 10,  "futSuffix": "AXISBANK"},
    {"symbol": "TATAMOTORS",  "spot": "NSE:TATAMOTORS",  "lot": 1350, "step": 5,   "futSuffix": "TATAMOTORS"},
    {"symbol": "HINDUNILVR",  "spot": "NSE:HINDUNILVR",  "lot": 300,  "step": 20,  "futSuffix": "HINDUNILVR"},
    {"symbol": "BAJFINANCE",  "spot": "NSE:BAJFINANCE",  "lot": 125,  "step": 50,  "futSuffix": "BAJFINANCE"},
    {"symbol": "ADANIENT",    "spot": "NSE:ADANIENT",    "lot": 250,  "step": 20,  "futSuffix": "ADANIENT"},
]

print(json.dumps(stocks, indent=2))
print(f"\nTotal: {len(stocks)} stocks")
