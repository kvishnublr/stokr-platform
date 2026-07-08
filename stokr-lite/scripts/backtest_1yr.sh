#!/bin/bash
# 1-year backtest for all daily strategies (₹1L capital)
STRATEGIES="OVERSOLD_BOUNCE EMA50_DISTANCE THREE_RED_DAYS THREE_DAY_MOMENTUM MICRO_V_REVERSAL MORNING_SURGE_REVERSAL"

for STRAT in $STRATEGIES; do
  echo "=== $STRAT ==="
  RESULT=$(curl -s -X POST "http://localhost:8081/api/backtest/advanced?strategy=$STRAT&dateStart=2025-07-08T00:00:00&dateEnd=2026-07-08T23:59:59&universe=NIFTY_50&timeframe=daily&capital=100000")
  echo "$RESULT" | python3 -c "
import sys,json
try:
  d=json.load(sys.stdin)
  print(f'  Trades: {d.get(\"totalTrades\")}')
  print(f'  Win Rate: {d.get(\"winRate\")}%')
  print(f'  Net PnL: {d.get(\"netPnL\")}')
  print(f'  Profit Factor: {d.get(\"profitFactor\")}')
  print(f'  Max Drawdown: {d.get(\"maxDrawdown\")}')
  print(f'  Avg PnL/Trade: {d.get(\"avgPnL\")}')
except Exception as e:
  print(f'  Error: {e}')
"
  echo ""
done
