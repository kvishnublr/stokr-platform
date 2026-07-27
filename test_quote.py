import requests, json, subprocess

# Get token from DB
result = subprocess.run(
    ['psql', '-h', 'localhost', '-U', 'postgres', '-d', 'stokr_lite', '-t', '-A', '-c',
     "SELECT access_token FROM broker_accounts WHERE id=4;"],
    capture_output=True, text=True, timeout=10,
    env={"PGPASSWORD": "`$POSTGRES_PASSWORD"}
)
token = result.stdout.strip()
print(f"Token: {token[:20]}...")

# Test quote API
headers = {
    "Authorization": f"token `$ZERODHA_API_KEY:{token}",
    "X-Kite-Version": "3"
}
resp = requests.get("https://api.kite.trade/quote?i=NSE:NIFTY%2050", headers=headers)
data = resp.json()
print(f"Status: {data.get('status')}")
print(f"Message: {data.get('message', 'none')}")
print(f"Data keys: {list(data.get('data', {}).keys())}")
if data.get('data'):
    for key, val in data['data'].items():
        print(f"  {key}: last_price={val.get('last_price', 'N/A')}, ohlc.close={val.get('ohlc', {}).get('close', 'N/A')}")

