import json, urllib.request, urllib.parse, time

def run_bt(strategy, universe="NIFTY_100", start="2026-04-10", end="2026-07-10", capital=33000):
    url = "http://localhost:8081/api/backtest/advanced"
    data = urllib.parse.urlencode({
        "strategy": strategy,
        "universe": universe,
        "dateStart": start,
        "dateEnd": end,
        "capital": str(capital),
        "initialCapital": "100000",
        "timeframe": "daily"
    }).encode()
    req = urllib.request.Request(url, data=data, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=180) as resp:
            return json.loads(resp.read())
    except Exception as e:
        return {"error": str(e)}

def short(label, r):
    trades = r.get("totalTrades", 0) or 0
    wr = r.get("winRate", 0) or 0
    net = r.get("netPnl", 0) or 0
    pf = r.get("profitFactor", 0) or 0
    dd = r.get("maxDrawdown", 0) or 0
    net_f = float(net) if net else 0
    pf_f = float(pf) if pf else 0
    tag = "WIN" if net_f > 100 else ("MARGINAL" if net_f > -100 else "LOSS")
    print(f"  {label:30s} | {trades:3d} trades | {wr:5.1f}% WR | Rs {net_f:>+10,.2f} | PF {pf_f:6.2f} | DD Rs {dd:>8} | {tag}")

# Strategies to test - unknown/marginal ones
test_strategies = [
    ("GAP_VWAP_RETEST", "GVR"),
    ("INSTITUTIONAL_FOOTPRINT", "IF"),
    ("INSTITUTIONAL_FOOTPRINT", "IF_v2"),  # same type in DB, test once
    ("NIFTY_CALENDAR_SPREAD", "NCS"),
    ("INSIDER_MOMENTUM", "IM"),
    ("GAP_CONTINUATION", "GC"),
    ("ORB_BREAKOUT_LONG", "OBL"),
    ("VWAP_REVERSION", "VWAP_REV"),
    ("VWAP_REJECTION", "VRS"),
    ("SMART_MONEY_FLOW", "SMF"),
    ("MOMENTUM_TRAIL", "MT"),
    ("GAP_REVERSAL", "GAP_REV"),
    ("VOLUME_SPIKE_MOMENTUM", "VSM"),
    ("INTRADAY_HIGH_BREAKOUT", "IHB"),
    ("VWAP_BOUNCE_V2", "VBL2"),
    ("SECTOR_ORB", "SORB"),
    ("THREE_DAY_MOMENTUM", "3DM"),
    ("NIFTY_PULSE", "NPA"),
    ("NPA_V2", "NPA_V2"),
    ("EOD_MOMENTUM", "EOD"),
    ("DEAD_CAT_BOUNCE", "DCB"),
    ("THREE_DAY_EXHAUSTION", "3DE"),
    ("VOLUME_COIL", "VCS"),
    ("OVERNIGHT_TRAP", "OT"),
    ("EMA_PULLBACK_SWING", "EPS"),
    ("TREND_CONTINUATION_BREAKOUT", "TCB"),
    ("TWENTY_DAY_BREAKOUT", "20DB"),
    ("EMA_CROSS_SWING", "ECS"),
    ("MICRO_V_REVERSAL", "MVR"),
    ("VWAP_GRID_SCALPER", "VGS"),
    ("VWAP_DIP_BUY", "VDB"),
    ("VWAP_BOUNCE_LONG", "VBL"),
    ("ORB_RETEST_LONG", "ORL"),
    ("CASH_IGNITION", "CLI"),
    ("AFTERNOON_BREAKOUT", "AB"),
    ("VWAP_SWING_MODE", "VSM2"),
    ("VWAP_BOUNCE_LONG_V2", "VBL3"),
    ("BULL_FLAG_BREAKOUT", "BFB"),
    ("BULL_MOMENTUM_ENTRY", "BME"),
    ("INSTITUTIONAL_ACCUMULATION", "IA"),
    ("NR4_BREAKOUT", "NR4"),
    ("MOMENTUM_GAP_UP", "MGU"),
    ("VWAP_REVERSAL_LONG", "VRL"),
    ("NR7_BREAKOUT", "NR7"),
    ("PREMIUM_DECAY_SHORT", "PDS"),
    ("CLOSING_RANGE_BREAKOUT", "CRB"),
]

print("=" * 100)
print("  BATCH BACKTEST: ALL STRATEGIES (NIFTY_100, 3 months, Rs 33K)")
print("=" * 100)

tested = set()
results = []

for strategy_type, short_name in test_strategies:
    if strategy_type in tested:
        continue
    tested.add(strategy_type)
    
    r = run_bt(strategy_type)
    if "error" in r:
        print(f"  {short_name:30s} | ERROR: {r['error'][:60]}")
        continue
    short(short_name, r)
    trades = r.get("trades", [])
    net = float(r.get("netPnl", 0) or 0)
    results.append((short_name, strategy_type, net, r))

print("\n" + "=" * 100)
print("  SUMMARY - SORTED BY NET P&L")
print("=" * 100)
results.sort(key=lambda x: x[2], reverse=True)
for name, stype, net, r in results:
    tag = "KEEP" if net > 100 else ("TUNE" if net > -200 else "REMOVE")
    print(f"  {name:30s} | Rs {net:>+10,.2f} | {tag}")

print("\n  KEEP: profitable (> +100)")
print("  TUNE: marginal (-200 to +100)")
print("  REMOVE: losing (< -200)")
