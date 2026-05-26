#!/usr/bin/env python3
"""
Enqueue synchronous backtest replays for active catalog strategies (14-day window by default).

Requires: API running, JWT in STOKR_BT_TOKEN, market data coverage READY for each symbol/timeframe.

Example:
  export STOKR_BT_TOKEN="eyJ..."
  export STOKR_API_BASE="http://localhost:8080"
  python scripts/run_active_strategy_backtests.py --symbol RELIANCE --days 14
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request
from datetime import datetime, timedelta, timezone

STRATEGIES = [
    ("NSE_SPIKE_DETECTION", "1m"),
    ("EARLY_BREAKOUT", "5m"),
    ("VWAP_BOUNCE", "1m"),
]

IST = timezone(timedelta(hours=5, minutes=30))


def main() -> int:
    p = argparse.ArgumentParser(description="Run catalog strategy backtests via /api/backtest/replay")
    p.add_argument("--symbol", default=os.environ.get("STOKR_BT_SYMBOL", "RELIANCE"))
    p.add_argument("--days", type=int, default=int(os.environ.get("STOKR_BT_DAYS", "14")))
    p.add_argument("--base", default=os.environ.get("STOKR_API_BASE", "http://localhost:8080"))
    args = p.parse_args()

    token = os.environ.get("STOKR_BT_TOKEN", "").strip()
    if not token:
        print("Set STOKR_BT_TOKEN to a valid JWT", file=sys.stderr)
        return 1

    end = datetime.now(IST).replace(hour=15, minute=30, second=0, microsecond=0)
    start = end - timedelta(days=args.days)
    start = start.replace(hour=9, minute=15)

    results = []
    for strategy_key, tf in STRATEGIES:
        body = {
            "strategyKey": strategy_key,
            "symbol": args.symbol,
            "timeframe": tf,
            "executionMode": "BACKTEST",
            "executionProfile": "INTRADAY_CASH",
            "feeModel": "ZERODHA_EQUITY",
            "slippageModel": "FIXED_BPS",
            "capital": 100000,
            "strategyParameters": {},
            "range": {
                "from": start.astimezone(timezone.utc).isoformat().replace("+00:00", "Z"),
                "to": end.astimezone(timezone.utc).isoformat().replace("+00:00", "Z"),
            },
        }
        try:
            out = post_json(f"{args.base.rstrip('/')}/api/backtest/replay", token, body)
            data = out.get("data") or {}
            metrics = data.get("metrics") or {}
            results.append({
                "strategy": strategy_key,
                "symbol": args.symbol,
                "timeframe": tf,
                "signals": data.get("signalsEmitted"),
                "trades": metrics.get("totalTrades"),
                "winRate": metrics.get("winRate"),
                "totalPnl": metrics.get("totalPnl"),
            })
            print(json.dumps(results[-1], indent=2))
        except urllib.error.HTTPError as e:
            print(f"{strategy_key} failed: {e.code} {e.read().decode()}", file=sys.stderr)
        except Exception as ex:
            print(f"{strategy_key} error: {ex}", file=sys.stderr)

    print("\n=== summary ===")
    print(json.dumps(results, indent=2))
    return 0 if results else 1


def post_json(url: str, token: str, payload: dict) -> dict:
    req = urllib.request.Request(
        url,
        data=json.dumps(payload).encode(),
        headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=600) as resp:
        return json.loads(resp.read().decode())


if __name__ == "__main__":
    raise SystemExit(main())
