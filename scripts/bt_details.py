#!/usr/bin/env python3
"""Get trade details for top strategies"""
import json

with open("/tmp/all_strategies_3month.json") as f:
    results = json.load(f)

top = [r for r in results if not r.get("error") and r.get("trades", 0) > 0]
top.sort(key=lambda x: x.get("net_pnl", 0), reverse=True)

# Print full trade-level data for top strategies
# First re-run to get actual trades
import requests

BASE = "http://localhost:8081"
r = requests.post(f"{BASE}/api/auth/login", json={"email": "vishnualgo@gmail.com", "password": "Temp@12345678"})
jwt = r.json()["accessToken"]
headers = {"Authorization": f"Bearer {jwt}"}

strategies = [
    ("RSI_OVERSOLD", "RSIO", "POSITIONAL"),
    ("THREE_RED_DAYS", "TRD", "POSITIONAL"),
    ("OVERSOLD_BOUNCE", "OB", "POSITIONAL"),
    ("MORNING_SURGE_REVERSAL", "MSR", "INTRA"),
    ("EMA50_DISTANCE", "EMA50D", "POSITIONAL"),
    ("GAP_CONTINUATION", "GAPC", "INTRA"),
    ("MICRO_V_REVERSAL", "MVR", "INTRA"),
]

for strat_type, short, cat in strategies:
    r = requests.post(f"{BASE}/api/backtest/advanced", data={
        "strategy": strat_type,
        "universe": "NIFTY_100",
        "dateStart": "2026-04-10",
        "dateEnd": "2026-07-10",
        "capital": 33000,
        "timeframe": "daily" if cat == "POSITIONAL" else "1min",
    }, headers=headers, timeout=120)
    
    if r.status_code != 200:
        continue
    
    data = r.json()
    trades = data.get("trades", [])
    
    print(f"\n{'='*100}")
    print(f"{short} ({cat}) — {len(trades)} trades, WR={data.get('winRate',0):.1f}%, Net P&L=₹{data.get('netPnL',0):.0f}, PF={data.get('profitFactor',0):.2f}, MaxDD=₹{data.get('maxDrawdown',0):.0f}")
    print(f"{'='*100}")
    print(f"{'#':>3s} {'SYMBOL':12s} {'SIDE':5s} {'ENTRY':>9s} {'EXIT':>9s} {'P&L':>8s} {'EXIT_TYPE':14s} {'ENTRY_DATE':20s}")
    print(f"{'-'*100}")
    
    for i, t in enumerate(trades):
        symbol = t.get("symbol", "?")
        side = t.get("side", "?")
        entry = t.get("entryPrice", 0)
        exit_p = t.get("exitPrice", 0)
        pnl = t.get("pnl", 0)
        exit_type = t.get("exitType", "?")
        entry_date = t.get("entryDate") or t.get("entry_time") or t.get("entryDateStr") or "?"
        
        pnl_str = f"+₹{pnl:.0f}" if pnl > 0 else f"₹{pnl:.0f}"
        marker = "✓" if pnl > 0 else "✗" if pnl < 0 else "="
        
        print(f"{i+1:3d} {symbol:12s} {side:5s} {entry:>9.2f} {exit_p:>9.2f} {pnl_str:>8s} {exit_type:14s} {str(entry_date):20s}")
