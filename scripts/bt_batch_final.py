import json, urllib.request, urllib.parse

def run_bt(strategy, universe="NIFTY_100", start="2026-04-10", end="2026-07-10", capital=33000):
    url = "http://localhost:8081/api/backtest/advanced"
    data = urllib.parse.urlencode({
        "strategy": strategy,
        "universe": universe,
        "dateStart": start,
        "dateEnd": end,
        "capital": str(capital),
        "initialCapital": "100000"
    }).encode()
    req = urllib.request.Request(url, data=data, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=180) as resp:
            return json.loads(resp.read())
    except Exception as e:
        return {"error": str(e)}

# All strategies to test (only the ones we haven't confirmed yet)
test_strategies = [
    "INSTITUTIONAL_FOOTPRINT",
    "NIFTY_CALENDAR_SPREAD",
    "INSIDER_MOMENTUM",
    "GAP_VWAP_RETEST",
    "GAP_CONTINUATION",
    "ORB_BREAKOUT_LONG",
    "VWAP_REVERSION",
    "VWAP_REJECTION",
    "SMART_MONEY_FLOW",
    "MOMENTUM_TRAIL",
    "GAP_REVERSAL",
    "VOLUME_SPIKE_MOMENTUM",
    "INTRADAY_HIGH_BREAKOUT",
    "SECTOR_ORB",
    "VWAP_GRID_SCALPER",
    "VWAP_DIP_BUY",
    "VWAP_BOUNCE_LONG",
    "ORB_RETEST_LONG",
    "CASH_IGNITION",
    "AFTERNOON_BREAKOUT",
    "THREE_DAY_MOMENTUM",
    "NIFTY_PULSE",
    "EOD_MOMENTUM",
    "THREE_DAY_EXHAUSTION",
    "EMA_PULLBACK_SWING",
    "TREND_CONTINUATION_BREAKOUT",
    "TWENTY_DAY_BREAKOUT",
    "EMA_CROSS_SWING",
    "MICRO_V_REVERSAL",
    "QUICK_FLIP",
    "BTST",
    "NPA_V2",
    "MORNING_SURGE_REVERSAL",
    "OVERSOLD_BOUNCE",
    "EMA50_DISTANCE",
    "THREE_RED_DAYS",
    "RSI_OVERSOLD",
]

print("=" * 120)
print("  COMPREHENSIVE BACKTEST — ALL STRATEGIES (NIFTY_100, 3 months, Rs 33K)")
print("=" * 120)
print(f"  {'STRATEGY':35s} | {'TF':8s} | {'TRADES':>6s} | {'WR':>6s} | {'NET PN&L':>12s} | {'BROKERAGE':>10s} | {'GROSS PnL':>12s} | {'PF':>6s} | {'MAX DD':>10s} | {'VERDICT':10s}")
print("-" * 120)

results = []
tested = set()

for strategy in test_strategies:
    if strategy in tested:
        continue
    tested.add(strategy)
    
    r = run_bt(strategy)
    if "error" in r:
        print(f"  {strategy:35s} | ERROR: {r['error'][:80]}")
        continue
    
    trades = r.get("totalTrades", 0) or 0
    wr = r.get("winRate", 0) or 0
    net_pnl = r.get("netPnL", 0) or 0
    gross_pnl = r.get("totalPnL", 0) or 0
    brokerage = r.get("totalBrokerage", 0) or 0
    pf = r.get("profitFactor", 0) or 0
    dd = r.get("maxDrawdown", 0) or 0
    tf = "daily" if "daily" in str(r.get("dateRange", "")).lower() else "intra"
    
    net_f = float(net_pnl) if net_pnl else 0
    gross_f = float(gross_pnl) if gross_pnl else 0
    brok_f = float(brokerage) if brokerage else 0
    pf_f = float(pf) if pf else 0
    
    if trades == 0:
        tag = "DEAD"
    elif net_f > 500:
        tag = "KEEP"
    elif net_f > -100:
        tag = "MARGINAL"
    elif pf_f > 1.5:
        tag = "TUNE"
    else:
        tag = "REMOVE"
    
    print(f"  {strategy:35s} | {tf:8s} | {trades:6d} | {wr:5.1f}% | Rs {net_f:>+10,.2f} | Rs {brok_f:>8,.2f} | Rs {gross_f:>+10,.2f} | {pf_f:5.2f} | Rs {dd:>8,.2f} | {tag}")
    results.append((strategy, net_f, gross_f, brok_f, pf_f, trades, dd, tag))

print("\n" + "=" * 120)
print("  FINAL VERDICT — SORTED BY NET P&L")
print("=" * 120)
results.sort(key=lambda x: x[1], reverse=True)

keep = [r for r in results if r[7] == "KEEP"]
marginal = [r for r in results if r[7] == "MARGINAL"]
tune = [r for r in results if r[7] == "TUNE"]
remove = [r for r in results if r[7] == "REMOVE"]
dead = [r for r in results if r[7] == "DEAD"]

if keep:
    print("\n  KEEP (net > +500):")
    for name, net, gross, brok, pf, trades, dd, _ in keep:
        print(f"    {name:35s} net=Rs {net:>+10,.2f} gross=Rs {gross:>+10,.2f} brok=Rs {brok:>8,.2f} PF={pf:.2f} trades={trades}")
if marginal:
    print("\n  MARGINAL (-100 to +500):")
    for name, net, gross, brok, pf, trades, dd, _ in marginal:
        print(f"    {name:35s} net=Rs {net:>+10,.2f} gross=Rs {gross:>+10,.2f} brok=Rs {brok:>8,.2f} PF={pf:.2f} trades={trades}")
if tune:
    print("\n  TUNE (losing but PF>1.5, potential):")
    for name, net, gross, brok, pf, trades, dd, _ in tune:
        print(f"    {name:35s} net=Rs {net:>+10,.2f} gross=Rs {gross:>+10,.2f} brok=Rs {brok:>8,.2f} PF={pf:.2f} trades={trades}")
if remove:
    print("\n  REMOVE (losing, PF<1.5):")
    for name, net, gross, brok, pf, trades, dd, _ in remove:
        print(f"    {name:35s} net=Rs {net:>+10,.2f} gross=Rs {gross:>+10,.2f} brok=Rs {brok:>8,.2f} PF={pf:.2f}")
if dead:
    print("\n  DEAD (0 trades):")
    for name, net, gross, brok, pf, trades, dd, _ in dead:
        print(f"    {name}")
