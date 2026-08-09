import json, urllib.request, math

RISK_FREE = 0.065

# Get live scan data
resp = urllib.request.urlopen('http://127.0.0.1:8081/api/option-arbitrage/history?strategyType=BID_PARITY&startDate=2026-08-06&endDate=2026-08-06&page=0&size=5')
data = json.loads(resp.read())

print(f"Total signals today: {data['totalElements']}")
print()

for item in data['items'][:5]:
    strike = item['strike']
    ce = float(item.get('ceEntryPrice') or item.get('cePrice') or 0)
    pe = float(item.get('peEntryPrice') or item.get('pePrice') or 0)
    fut = float(item.get('futuresPrice') or 0)
    spot = float(item.get('spotPrice') or 0)
    edge_after = float(item.get('edgeAfterCosts') or 0)
    edge_points = float(item.get('edgePoints') or 0)
    action = item.get('action', '')
    expiry = item.get('expiryDate', '')

    # Manual parity calculation
    synthetic = ce - pe + strike
    parity_dev = synthetic - fut
    parity_dev_abs = abs(parity_dev)

    print(f"Strike={strike} Action={action[:30]}")
    print(f"  CE={ce} PE={pe} FUT={fut} Spot={spot}")
    print(f"  Expiry={expiry}")
    print(f"  Synthetic(CE-PE+K) = {ce} - {pe} + {strike} = {synthetic:.1f}")
    print(f"  Parity Deviation = {synthetic:.1f} - {fut} = {parity_dev:.1f} points")
    print(f"  Edge Points (from API) = {edge_points}")
    print(f"  Edge After Costs (from API) = {edge_after}")
    print(f"  Manual gross edge (abs(dev)*25) = {parity_dev_abs * 25:.0f}")
    print()

    # Check intrinsic values
    ce_intrinsic = max(0, fut - strike)
    pe_intrinsic = max(0, strike - fut)
    print(f"  CE intrinsic (FUT-K) = {fut} - {strike} = {ce_intrinsic:.1f} (CE price={ce})")
    print(f"  PE intrinsic (K-FUT) = {strike} - {fut} = {pe_intrinsic:.1f} (PE price={pe})")
    if ce < ce_intrinsic and ce_intrinsic > 0:
        print(f"  *** BUG: CE price ({ce}) < intrinsic ({ce_intrinsic:.1f}) — BELOW intrinsic value! ***")
    if pe < pe_intrinsic and pe_intrinsic > 0:
        print(f"  *** BUG: PE price ({pe}) < intrinsic ({pe_intrinsic:.1f}) — BELOW intrinsic value! ***")
    print()
