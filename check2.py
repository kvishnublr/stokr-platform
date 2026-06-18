import paramiko, json

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("173.249.55.84", username="root", key_filename=r"C:\Users\itsvi\.ssh\id_rsa_stokr", timeout=15)

def curl(method, path, data=None, token=None):
    headers = "-H 'Content-Type: application/json'"
    if token:
        headers += f" -H 'Authorization: Bearer {token}'"
    body = ""
    if data:
        escaped = json.dumps(data).replace("'", "\\'")
        body = f"-d '{escaped}'"
    cmd = f"curl -s -X {method} http://localhost:8070{path} {headers} {body}"
    _, stdout, stderr = ssh.exec_command(cmd)
    resp = stdout.read().decode().strip()
    try:
        return json.loads(resp)
    except:
        return resp

print("=== Login ===")
r = curl("POST", "/api/auth/login", {"email": "trader@stokr.in", "password": "Trader@123"})
print(r)
token = r.get("accessToken", "")
uid = r.get("userId", 4)

print("\n=== TraderConfig ===")
r = curl("GET", f"/api/chartink/trader-config/{uid}", token=token)
print(r)

print("\n=== Deploy PAPER ===")
r = curl("POST", "/api/deployments", {"strategyId": 1, "mode": "PAPER", "capital": 15000}, token=token)
print(r)

print("\n=== Deployments list ===")
r = curl("GET", "/api/deployments", token=token)
if isinstance(r, list):
    print(f"Count: {len(r)}")
else:
    print(r)

print("\n=== Webhook ===")
wh = {
    "scannerName": "STOKR_ORB_V_BREAKOUT", "symbol": "TCS", "ltp": 4200.0,
    "volume": 80000, "buyerQty": 50000, "sellerQty": 30000, "changePct": 0.8,
    "vwapDeviationPct": 0.1, "atr14": 22.0, "adx14": 30.0, "rvol": 1.5,
    "open": 4180.0, "high": 4210.0, "low": 4175.0, "close": 4198.0,
    "prevClose": 4165.0, "bestBid": 4199.0, "bestAsk": 4201.0,
    "bidQty": 2000, "askQty": 1800, "niftyChangePct": 0.3,
    "stockCategory": "LARGECAP", "timestamp": "2026-06-18T09:45:00Z", "triggerType": "SCANNER_HIT"
}
r = curl("POST", "/webhooks/chartink/intraday", wh)
print(r)

ssh.close()
print("\nDONE")
