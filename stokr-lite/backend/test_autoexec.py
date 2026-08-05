import urllib.request, json, time, datetime

ist = datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=5, minutes=30)))
print("Current IST time:", ist.strftime("%H:%M:%S"))

# Trigger scan
resp = urllib.request.urlopen('http://127.0.0.1:8081/api/option-arbitrage/scan?underlying=NIFTY')
scan_data = json.loads(resp.read().decode())
print("Scan opportunities:", scan_data.get("count", 0))
if scan_data.get("opportunities"):
    for o in scan_data["opportunities"][:3]:
        print("  -", o.get("underlying"), o.get("strike"), "edge=", o.get("edgeAfterCosts"), "action=", o.get("action"), "type=", o.get("strategyType") or o.get("type"))

time.sleep(1)

# Check exec logs
resp2 = urllib.request.urlopen('http://127.0.0.1:8081/api/option-arbitrage/auto-execute/logs')
logs = json.loads(resp2.read().decode())
print("Exec logs:", len(logs))
for l in logs:
    print("  - [" + l.get("status", "") + "]", l.get("type", "") + ":", l.get("message", ""))

# Check live positions
resp3 = urllib.request.urlopen('http://127.0.0.1:8081/api/option-arbitrage/live-positions')
pos = json.loads(resp3.read().decode())
print("Live positions:", pos.get("count", 0))
