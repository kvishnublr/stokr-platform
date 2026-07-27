#!/usr/bin/env python3
"""Comprehensive 3-month backtest for ALL strategies â€” classify intra vs positional and rank"""
import requests
import json
import sys
import time

BASE = "http://localhost:8081"
r = requests.post(f"{BASE}/api/auth/login", json={"email": "vishnualgo@gmail.com", "password": "`$ADMIN_PASSWORD"})
jwt = r.json()["accessToken"]
headers = {"Authorization": f"Bearer {jwt}"}

# All strategies that can be backtested via /api/backtest/advanced
# Grouped by daily (positional) vs intraday
DAILY_STRATEGIES = [
    ("OVERSOLD_BOUNCE",       "OB",    "POSITIONAL"),
    ("EMA50_DISTANCE",        "EMA50D","POSITIONAL"),
    ("THREE_RED_DAYS",        "TRD",   "POSITIONAL"),
    ("THREE_DAY_MOMENTUM",    "3DM",   "POSITIONAL"),
    ("RSI_OVERSOLD",          "RSIO",  "POSITIONAL"),
    ("DEAD_CAT_BOUNCE",       "DCB",   "POSITIONAL"),
    ("THREE_DAY_EXHAUSTION",  "3DE",   "POSITIONAL"),
    ("EMA_PULLBACK_SWING",    "EPS",   "POSITIONAL"),
    ("TWENTY_DAY_BREAKOUT",   "20DB",  "POSITIONAL"),
    ("EMA_CROSS_SWING",       "ECS",   "POSITIONAL"),
    ("TREND_CONTINUATION_BREAKOUT","TCB","POSITIONAL"),
    ("EOD_MOMENTUM",          "EOD",   "POSITIONAL"),
    ("NIFTY_PULSE",           "NPA",   "POSITIONAL"),
    ("NPA_V2",                "NPA2",  "POSITIONAL"),
    ("SMART_MONEY_FLOW",      "SMF",   "POSITIONAL"),
    ("MOMENTUM_TRAIL",        "MT",    "POSITIONAL"),
]

INTRADAY_STRATEGIES = [
    ("MORNING_SURGE_REVERSAL","MSR",   "INTRA"),
    ("MICRO_V_REVERSAL",      "MVR",   "INTRA"),
    ("VWAP_REVERSION",        "VWR",   "INTRA"),
    ("AFTERNOON_BREAKOUT",    "AB",    "INTRA"),
    ("VWAP_BOUNCE_LONG",      "VBL",   "INTRA"),
    ("VWAP_REJECTION",        "VRS",   "INTRA"),
    ("VWAP_DIP_BUY",          "VDB",   "INTRA"),
    ("INTRADAY_HIGH_BREAKOUT","IHB",   "INTRA"),
    ("ORB_BREAKOUT_LONG",     "OBL",   "INTRA"),
    ("ORB_RETEST_LONG",       "ORL",   "INTRA"),
    ("GAP_REVERSAL",          "GAPR",  "INTRA"),
    ("GAP_CONTINUATION",      "GAPC",  "INTRA"),
    ("VOLUME_SPIKE_MOMENTUM", "VSM",   "INTRA"),
    ("VOLUME_COIL",           "VCOIL", "INTRA"),
    ("SECTOR_ORB",            "SORB",  "INTRA"),
    ("CASH_IGNITION",         "CLI",   "INTRA"),
    ("VWAP_GRID_SCALPER",     "VGS",   "INTRA"),
    ("GAP_VWAP_RETEST",       "GVR",   "INTRA"),
    ("OVERNIGHT_TRAP",        "OT",    "INTRA"),
]

ALL = DAILY_STRATEGIES + INTRADAY_STRATEGIES
DATE_START = "2026-04-10"
DATE_END = "2026-07-10"
CAPITAL = 33000

results = []

for strat_type, short, cat in ALL:
    sys.stdout.write(f"  {short:8s} ({cat:10s})... ")
    sys.stdout.flush()
    
    try:
        r = requests.post(f"{BASE}/api/backtest/advanced", data={
            "strategy": strat_type,
            "universe": "NIFTY_100",
            "dateStart": DATE_START,
            "dateEnd": DATE_END,
            "capital": CAPITAL,
            "timeframe": "daily" if cat == "POSITIONAL" else "1min",
        }, headers=headers, timeout=120)
        
        if r.status_code != 200:
            print(f"ERROR {r.status_code}: {r.text[:100]}")
            results.append({"short": short, "type": strat_type, "cat": cat, "error": True})
            continue
        
        data = r.json()
        trades = data.get("trades", [])
        total_trades = data.get("totalTrades", len(trades))
        win_count = data.get("winCount", 0)
        loss_count = data.get("lossCount", 0)
        total_pnl = data.get("totalPnL", 0)
        net_pnl = data.get("netPnL", total_pnl)
        win_rate = data.get("winRate", 0)
        avg_pnl = data.get("avgPnL", 0)
        max_dd = data.get("maxDrawdown", 0)
        pf = data.get("profitFactor", 0)
        total_brokerage = data.get("totalBrokerage", 0)
        
        # Calculate additional stats from trades
        if trades:
            wins = [t for t in trades if t.get("pnl", 0) > 0]
            losses = [t for t in trades if t.get("pnl", 0) < 0]
            avg_win = sum(t["pnl"] for t in wins) / len(wins) if wins else 0
            avg_loss = sum(t["pnl"] for t in losses) / len(losses) if losses else 0
            max_single_loss = min((t.get("pnl", 0) for t in trades), default=0)
            max_single_win = max((t.get("pnl", 0) for t in trades), default=0)
        else:
            avg_win = avg_loss = max_single_loss = max_single_win = 0
        
        # Monthly breakdown
        monthly = {}
        for t in trades:
            ed = t.get("entryDate") or t.get("entry_time") or ""
            if ed:
                month = str(ed)[:7]  # YYYY-MM
                if month not in monthly:
                    monthly[month] = {"trades": 0, "pnl": 0, "wins": 0}
                monthly[month]["trades"] += 1
                monthly[month]["pnl"] += t.get("pnl", 0)
                if t.get("pnl", 0) > 0:
                    monthly[month]["wins"] += 1
        
        profitable_months = sum(1 for m in monthly.values() if m["pnl"] > 0)
        total_months = len(monthly) if monthly else 1
        monthly_win_rate = (profitable_months / total_months * 100) if total_months > 0 else 0
        
        # Consecutive losses
        max_consec_loss = 0
        cur_consec = 0
        for t in trades:
            if t.get("pnl", 0) < 0:
                cur_consec += 1
                max_consec_loss = max(max_consec_loss, cur_consec)
            else:
                cur_consec = 0
        
        result = {
            "short": short,
            "type": strat_type,
            "cat": cat,
            "trades": total_trades,
            "wins": win_count,
            "losses": loss_count,
            "win_rate": win_rate,
            "total_pnl": total_pnl,
            "net_pnl": net_pnl,
            "avg_pnl": avg_pnl,
            "max_dd": max_dd,
            "pf": pf,
            "brokerage": total_brokerage,
            "avg_win": avg_win,
            "avg_loss": avg_loss,
            "max_win": max_single_win,
            "max_loss": max_single_loss,
            "monthly_wr": monthly_win_rate,
            "profitable_months": profitable_months,
            "total_months": total_months,
            "max_consec_loss": max_consec_loss,
            "monthly": monthly,
        }
        results.append(result)
        print(f"T={total_trades:3d} WR={win_rate:5.1f}% PnL={net_pnl:>9.0f} PF={pf:5.2f} DD={max_dd:>7.0f}")
        
    except Exception as e:
        print(f"EXCEPTION: {e}")
        results.append({"short": short, "type": strat_type, "cat": cat, "error": True})
    
    time.sleep(0.5)

# Save full results to JSON
with open("/tmp/all_strategies_3month.json", "w") as f:
    json.dump(results, f, indent=2, default=str)

# Print summary
print("\n" + "="*120)
print(f"{'STRATEGY':12s} {'TYPE':10s} {'TRADES':>6s} {'WR%':>6s} {'NET P&L':>10s} {'PF':>6s} {'MAX DD':>8s} {'AVG WIN':>8s} {'AVG LOSS':>9s} {'M+WR%':>6s} {'MAX CONC':>8s}")
print("="*120)

# Sort by net_pnl descending
sorted_results = sorted([r for r in results if not r.get("error")], key=lambda x: x.get("net_pnl", 0), reverse=True)

for r in sorted_results:
    print(f"{r['short']:12s} {r['cat']:10s} {r['trades']:6d} {r['win_rate']:5.1f}% {r['net_pnl']:>10.0f} {r['pf']:5.2f} {r['max_dd']:>8.0f} {r['avg_win']:>8.0f} {r['avg_loss']:>9.0f} {r['monthly_wr']:5.1f}% {r['max_consec_loss']:>8d}")

print("="*120)
print(f"\nResults saved to /tmp/all_strategies_3month.json")
print(f"Period: {DATE_START} to {DATE_END}, Capital per trade: â‚¹{CAPITAL}, Universe: NIFTY_100")

