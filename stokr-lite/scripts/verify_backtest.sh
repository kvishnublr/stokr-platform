#!/bin/bash
# Verify backtest for THREE_RED_DAYS
RESULT=$(curl -s -X POST 'http://localhost:8081/api/backtest/advanced?strategy=THREE_RED_DAYS&dateStart=2026-04-07T00:00:00&dateEnd=2026-07-07T23:59:59&universe=NIFTY_50&timeframe=daily&capital=100000')
echo "THREE_RED_DAYS 3-month:"
echo "$RESULT" | python3 -c "import sys,json; d=json.load(sys.stdin); print(f'  Trades: {d.get(\"totalTrades\")}, Win Rate: {d.get(\"winRate\")}%, Net PnL: {d.get(\"netPnL\")}, PF: {d.get(\"profitFactor\")}, Max DD: {d.get(\"maxDrawdown\")}')"

# Verify backtest for EMA50_DISTANCE v2
RESULT2=$(curl -s -X POST 'http://localhost:8081/api/backtest/advanced?strategy=EMA50_DISTANCE&dateStart=2026-04-07T00:00:00&dateEnd=2026-07-07T23:59:59&universe=NIFTY_50&timeframe=daily&capital=100000')
echo "EMA50_DISTANCE v2 3-month:"
echo "$RESULT2" | python3 -c "import sys,json; d=json.load(sys.stdin); print(f'  Trades: {d.get(\"totalTrades\")}, Win Rate: {d.get(\"winRate\")}%, Net PnL: {d.get(\"netPnL\")}, PF: {d.get(\"profitFactor\")}, Max DD: {d.get(\"maxDrawdown\")}')"
