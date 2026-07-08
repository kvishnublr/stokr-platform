#!/usr/bin/env python3
"""Run 3-year backtests for all strategies — FIXED: use 'universe' not 'indexName'"""
import urllib.request
import json

BASE = "http://localhost:8081/api/backtest/advanced"
STRATEGIES = ["OVERSOLD_BOUNCE", "EMA50_DISTANCE", "MORNING_SURGE_REVERSAL", "MICRO_V_REVERSAL", "THREE_DAY_MOMENTUM", "THREE_RED_DAYS"]
CAPITAL = 100000
START = "2023-07-10"
END = "2026-07-08"
INDICES = ["NIFTY_50", "NIFTY_100"]

print("=" * 90)
print(f"3-YEAR BACKTEST: {START} to {END}, Capital: Rs{CAPITAL:,}")
print("=" * 90)

results = {}
for idx in INDICES:
    print(f"\n{'='*90}")
    print(f"  INDEX: {idx}")
    print(f"{'='*90}")
    for strat in STRATEGIES:
        url = f"{BASE}?strategy={strat}&universe={idx}&capital={CAPITAL}&dateStart={START}&dateEnd={END}&timeframe=daily"
        try:
            req = urllib.request.Request(url, method='POST')
            with urllib.request.urlopen(req, timeout=120) as resp:
                d = json.loads(resp.read())
            
            wt = d.get("winningTrades", 0)
            lt = d.get("losingTrades", 0)
            tt = d.get("totalTrades", 0)
            wr = d.get("winRate", 0)
            pnl = d.get("netPnL", 0)
            dd = d.get("maxDrawdown", 0)
            pf = d.get("profitFactor", 0)
            avg = d.get("avgTradePnL", 0)
            maxHold = d.get("maxHoldDays", 0)
            
            results[f"{idx}_{strat}"] = {"trades": tt, "wr": wr, "pnl": pnl, "pf": pf, "dd": dd}
            
            print(f"\n  {strat}")
            print(f"  Trades: {tt} | WR: {wr:.1f}% | Net PnL: Rs{pnl:,.0f} | PF: {pf:.2f}")
            print(f"  MaxDD: Rs{dd:,.0f} | Avg/Trade: Rs{avg:,.0f} | W: {wt} L: {lt} | MaxHold: {maxHold}d")
        except Exception as e:
            print(f"\n  {strat}: ERROR - {e}")

# Summary
print(f"\n{'='*90}")
print("SUMMARY (sorted by Net PnL)")
print(f"{'='*90}")
sorted_results = sorted(results.items(), key=lambda x: x[1]['pnl'], reverse=True)
for k, v in sorted_results:
    print(f"  {k:40s} | Trades: {v['trades']:4d} | WR: {v['wr']:5.1f}% | PnL: Rs{v['pnl']:>10,.0f} | PF: {v['pf']:.2f} | MaxDD: Rs{v['dd']:>8,.0f}")
