import json, urllib.request

for u in ["NIFTY", "MIDCPNIFTY", "FINNIFTY", "ALL"]:
    try:
        d = json.loads(urllib.request.urlopen(f"http://localhost:8081/api/option-arbitrage/scan?underlying={u}", timeout=60).read())
        print(f"{u}: status={d.get('status')}, opps={d.get('totalOpportunities', 0)}")
    except Exception as e:
        print(f"{u}: ERROR {e}")

# Calendar spread
try:
    d = json.loads(urllib.request.urlopen("http://localhost:8081/api/option-arbitrage/calendar-spread?underlying=NIFTY", timeout=60).read())
    print(f"Calendar: status={d.get('status')}, spreads={d.get('totalSpreads', 0)}")
except Exception as e:
    print(f"Calendar: ERROR {e}")

# Vol surface
try:
    d = json.loads(urllib.request.urlopen("http://localhost:8081/api/option-arbitrage/vol-surface?underlying=NIFTY", timeout=60).read())
    s = d.get("summary", {})
    print(f"VolSurface: status={d.get('status')}, weeklyIV={s.get('avgWeeklyIV')}%, monthlyIV={s.get('avgMonthlyIV')}%")
    print(f"  signals: vol={s.get('volSignal')}, skew={s.get('skewSignal')}, term={s.get('termSignal')}")
    print(f"  surface strikes: {len(d.get('surface', []))}")
except Exception as e:
    print(f"VolSurface: ERROR {e}")
