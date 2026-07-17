import json, urllib.request

d = json.loads(urllib.request.urlopen("http://localhost:8081/api/option-arbitrage/vol-surface?underlying=NIFTY").read())
surface = d.get("surface", [])
print(f"Strikes: {len(surface)}")
for r in surface[:5]:
    print(f"  {r.get('strike')}: WCE={r.get('weeklyCE_IV','N/A')} WPE={r.get('weeklyPE_IV','N/A')} MCE={r.get('monthlyCE_IV','N/A')} MPE={r.get('monthlyPE_IV','N/A')}")
print(f"Summary: {json.dumps(d.get('summary',{}), indent=2)}")
