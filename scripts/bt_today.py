#!/usr/bin/env python3
"""Run backtest for today (Jul 10) for all 3 live strategies"""
import requests
import json

BASE = "http://localhost:8081"

# Login first
r = requests.post(f"{BASE}/api/auth/login", json={"email": "vishnualgo@gmail.com", "password": "Temp@12345678"})
jwt = r.json()["accessToken"]
headers = {"Authorization": f"Bearer {jwt}"}

strategies = [
    ("EMA50_DISTANCE", "EMA50D"),
    ("OVERSOLD_BOUNCE", "OB"),
    ("THREE_RED_DAYS", "TRD"),
]

for strat_type, label in strategies:
    print(f"\n{'='*60}")
    print(f"Running backtest: {label} ({strat_type})")
    print(f"{'='*60}")
    
    params = {
        "strategy": strat_type,
        "universe": "NIFTY_100",
        "dateStart": "2026-06-01",
        "dateEnd": "2026-07-10",
        "capital": 33000,
        "brokerage": 80
    }
    
    try:
        r = requests.post(f"{BASE}/api/backtest/advanced", data=params, headers=headers, timeout=120)
        if r.status_code == 200:
            data = r.json()
            trades = data.get("trades", [])
            summary = data.get("summary", {})
            print(f"Trades: {len(trades)}")
            print(f"Win Rate: {summary.get('winRate', 'N/A')}")
            print(f"Net P&L: {summary.get('netPnl', 'N/A')}")
            print(f"Profit Factor: {summary.get('profitFactor', 'N/A')}")
            if trades:
                print(f"\nRecent trades:")
                for t in trades[-10:]:
                    symbol = t.get("symbol", "?")
                    side = t.get("side", "?")
                    entry = t.get("entryPrice", 0)
                    exit_p = t.get("exitPrice", 0)
                    pnl = t.get("pnl", 0)
                    exit_type = t.get("exitType", "?")
                    entry_date = t.get("entryDate", "?")
                    print(f"  {symbol:12} {side:4} entry={entry:>8.2f} exit={exit_p:>8.2f} P&L={pnl:>8.2f} ({exit_type}) {entry_date}")
            else:
                print("  No trades!")
        else:
            print(f"ERROR {r.status_code}: {r.text[:200]}")
    except Exception as e:
        print(f"EXCEPTION: {e}")
