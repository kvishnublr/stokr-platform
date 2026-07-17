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
        # NOTE: no timeframe param — let server auto-detect
    }).encode()
    req = urllib.request.Request(url, data=data, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=180) as resp:
            return json.loads(resp.read())
    except Exception as e:
        return {"error": str(e)}

# All unique strategy types from BacktestController STRATEGY_PLUGIN_MAP
# Skip ones we already know are profitable (OB, EMA50D, TRD, RSIO, MSR)
# and known losers (VWAP losers, CASH_IGNITION)
# Testing: 0-trade strategies + marginal ones
test_strategies = [
    # Unknown - never tested
    "INSTITUTIONAL_FOOTPRINT",
    "NIFTY_CALENDAR_SPREAD",
    "INSIDER_MOMENTUM",
    # 0 trades on daily - probably need 1min
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
    "VWAP_BOUNCE_V2",
    "SECTOR_ORB",
    "VWAP_GRID_SCALPER",
    "VWAP_DIP_BUY",
    "VWAP_BOUNCE_LONG",
    "ORB_RETEST_LONG",
    "CASH_IGNITION",
    "AFTERNOON_BREAKOUT",
    "VOLUME_COIL",
    "OVERNIGHT_TRAP",
    "DEAD_CAT_BOUNCE",
    "DEAD_CAT_BOUNCE",
    # Mixed results
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
    "MOMENTUM_SURGE",
    "BTST",
    "NPA_V2",
]

tested = set()
results = []

print("=" * 100)
print("  BATCH BACKTEST: ALL STRATEGIES (NIFTY_100, 3 months, Rs 33K, auto-timeframe)")
print("=" * 100)

for strategy in test_strategies:
    if strategy in tested:
        continue
    tested.add(strategy)
    
    r = run_bt(strategy)
    if "error" in r:
        print(f"  {strategy:35s} | ERROR: {r['error'][:60]}")
        continue
    
    trades = r.get("totalTrades", 0) or 0
    wr = r.get("winRate", 0) or 0
    net = r.get("netPnl", 0) or 0
    pf = r.get("profitFactor", 0) or 0
    dd = r.get("maxDrawdown", 0) or 0
    tf = r.get("timeframe", "?")
    net_f = float(net) if net else 0
    pf_f = float(pf) if pf else 0
    
    tag = "KEEP" if net_f > 100 else ("TUNE" if net_f > -200 else "REMOVE")
    if trades == 0:
        tag = "DEAD (0 trades)"
    
    print(f"  {strategy:35s} | {tf:8s} | {trades:3d} trades | {wr:5.1f}% WR | Rs {net_f:>+10,.2f} | PF {pf_f:6.2f} | DD Rs {dd:>8} | {tag}")
    results.append((strategy, net_f, pf_f, trades, tag))

print("\n" + "=" * 100)
print("  VERDICT")
print("=" * 100)
results.sort(key=lambda x: x[1], reverse=True)
keep = [r for r in results if r[4] == "KEEP"]
tune = [r for r in results if r[4] == "TUNE"]
dead = [r for r in results if r[4] == "DEAD (0 trades)"]
remove = [r for r in results if r[4] == "REMOVE"]

if keep:
    print("\n  KEEP (profitable):")
    for name, net, pf, trades, _ in keep:
        print(f"    {name:35s} Rs {net:>+10,.2f} PF {pf:.2f}")
if tune:
    print("\n  TUNE (marginal):")
    for name, net, pf, trades, _ in tune:
        print(f"    {name:35s} Rs {net:>+10,.2f} PF {pf:.2f}")
if dead:
    print("\n  DEAD (0 trades — intraday on daily data, or no setups):")
    for name, net, pf, trades, _ in dead:
        print(f"    {name}")
if remove:
    print("\n  REMOVE (losing money):")
    for name, net, pf, trades, _ in remove:
        print(f"    {name:35s} Rs {net:>+10,.2f}")
