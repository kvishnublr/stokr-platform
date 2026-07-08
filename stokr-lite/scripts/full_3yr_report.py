#!/usr/bin/env python3
"""Clean 3-year backtest report: NIFTY_50 vs NIFTY_100"""
import urllib.request
import json

BASE = "http://localhost:8081/api/backtest/advanced"
STRATEGIES = ["OVERSOLD_BOUNCE", "EMA50_DISTANCE", "MORNING_SURGE_REVERSAL", "MICRO_V_REVERSAL", "THREE_DAY_MOMENTUM", "THREE_RED_DAYS"]
STRAT_NAMES = {
    "OVERSOLD_BOUNCE": "Oversold Bounce",
    "EMA50_DISTANCE": "EMA50 Distance",
    "MORNING_SURGE_REVERSAL": "Morning Surge Reversal",
    "MICRO_V_REVERSAL": "Micro V-Reversal",
    "THREE_DAY_MOMENTUM": "3-Day Momentum",
    "THREE_RED_DAYS": "3 Red Days"
}
CAPITAL = 100000
START = "2023-07-10"
END = "2026-07-08"

def run_backtest(strat, universe):
    url = f"{BASE}?strategy={strat}&universe={universe}&capital={CAPITAL}&dateStart={START}&dateEnd={END}&timeframe=daily"
    req = urllib.request.Request(url, method='POST')
    with urllib.request.urlopen(req, timeout=120) as resp:
        return json.loads(resp.read())

print("=" * 100)
print(f"  3-YEAR BACKTEST REPORT: {START} to {END}  |  Capital: Rs{CAPITAL:,}")
print("=" * 100)

# ==================== NIFTY_50 ====================
print("\n" + "=" * 100)
print("  NIFTY 50")
print("=" * 100)
print(f"  {'Strategy':<25s} {'Trades':>7s} {'WR%':>7s} {'Net PnL':>12s} {'PF':>7s} {'MaxDD':>10s} {'Avg/Trade':>10s}")
print("  " + "-" * 93)

nifty50_results = []
for strat in STRATEGIES:
    d = run_backtest(strat, "NIFTY_50")
    tt = d.get("totalTrades", 0)
    wr = d.get("winRate", 0)
    pnl = d.get("netPnL", 0)
    pf = d.get("profitFactor", 0)
    dd = d.get("maxDrawdown", 0)
    avg = d.get("avgTradePnL", 0)
    name = STRAT_NAMES.get(strat, strat)
    nifty50_results.append((name, tt, wr, pnl, pf, dd, avg))
    print(f"  {name:<25s} {tt:>7d} {wr:>6.1f}% {pnl:>11,.0f} {pf:>7.2f} {dd:>9,.0f} {avg:>10,.0f}")

print("  " + "-" * 93)
total_pnl = sum(r[3] for r in nifty50_results)
total_trades = sum(r[1] for r in nifty50_results)
print(f"  {'TOTAL':<25s} {total_trades:>7d} {'':>7s} {total_pnl:>11,.0f}")

# ==================== NIFTY_100 ====================
print("\n" + "=" * 100)
print("  NIFTY 100")
print("=" * 100)
print(f"  {'Strategy':<25s} {'Trades':>7s} {'WR%':>7s} {'Net PnL':>12s} {'PF':>7s} {'MaxDD':>10s} {'Avg/Trade':>10s}")
print("  " + "-" * 93)

nifty100_results = []
for strat in STRATEGIES:
    d = run_backtest(strat, "NIFTY_100")
    tt = d.get("totalTrades", 0)
    wr = d.get("winRate", 0)
    pnl = d.get("netPnL", 0)
    pf = d.get("profitFactor", 0)
    dd = d.get("maxDrawdown", 0)
    avg = d.get("avgTradePnL", 0)
    name = STRAT_NAMES.get(strat, strat)
    nifty100_results.append((name, tt, wr, pnl, pf, dd, avg))
    print(f"  {name:<25s} {tt:>7d} {wr:>6.1f}% {pnl:>11,.0f} {pf:>7.2f} {dd:>9,.0f} {avg:>10,.0f}")

print("  " + "-" * 93)
total_pnl = sum(r[3] for r in nifty100_results)
total_trades = sum(r[1] for r in nifty100_results)
print(f"  {'TOTAL':<25s} {total_trades:>7d} {'':>7s} {total_pnl:>11,.0f}")

# ==================== SIDE BY SIDE ====================
print("\n" + "=" * 100)
print("  NIFTY 50 vs NIFTY 100 COMPARISON")
print("=" * 100)
print(f"  {'Strategy':<25s} {'N50 PnL':>12s} {'N100 PnL':>12s} {'Diff':>10s} {'N50 WR':>8s} {'N100 WR':>8s} {'N50 PF':>8s} {'N100 PF':>8s}")
print("  " + "-" * 93)

for i, strat in enumerate(STRATEGIES):
    name = STRAT_NAMES.get(strat, strat)
    n50 = nifty50_results[i]
    n100 = nifty100_results[i]
    diff = n100[3] - n50[3]
    print(f"  {name:<25s} {n50[3]:>11,.0f} {n100[3]:>11,.0f} {diff:>+9,.0f} {n50[2]:>7.1f}% {n100[2]:>7.1f}% {n50[4]:>8.2f} {n100[4]:>8.2f}")

print("  " + "-" * 93)
n50_total = sum(r[3] for r in nifty50_results)
n100_total = sum(r[3] for r in nifty100_results)
print(f"  {'TOTAL':<25s} {n50_total:>11,.0f} {n100_total:>11,.0f} {n100_total - n50_total:>+9,.0f}")
