#!/bin/bash
API="http://localhost:8081/api/backtest/advanced"

echo "=== EMA50_DISTANCE on NIFTY_50 (Jul 2025 - Jul 2026, ₹1L) ==="
curl -s -X POST "${API}?strategy=EMA50_DISTANCE&universe=NIFTY_50&dateStart=2025-07-01&dateEnd=2026-07-07&capital=100000" | python3 -c "
import sys, json
d = json.load(sys.stdin)
for k in ['strategy','totalTrades','winRate','netPnL','profitFactor','maxDrawdown','avgPnL','maxConsecutiveLosses','totalBrokerage','candlesLoaded']:
    if k in d: print(f'  {k}: {d[k]}')
if 'error' in d: print(f'  ERROR: {d[\"error\"]}')
"
echo ""

echo "=== RSI_OVERSOLD on NIFTY_50 (Jul 2025 - Jul 2026, ₹1L) ==="
curl -s -X POST "${API}?strategy=RSI_OVERSOLD&universe=NIFTY_50&dateStart=2025-07-01&dateEnd=2026-07-07&capital=100000" | python3 -c "
import sys, json
d = json.load(sys.stdin)
for k in ['strategy','totalTrades','winRate','netPnL','profitFactor','maxDrawdown','avgPnL','maxConsecutiveLosses','totalBrokerage','candlesLoaded']:
    if k in d: print(f'  {k}: {d[k]}')
if 'error' in d: print(f'  ERROR: {d[\"error\"]}')
"
echo ""

echo "=== OVERSOLD_BOUNCE baseline ==="
curl -s -X POST "${API}?strategy=OVERSOLD_BOUNCE&universe=NIFTY_50&dateStart=2025-07-01&dateEnd=2026-07-07&capital=100000" | python3 -c "
import sys, json
d = json.load(sys.stdin)
for k in ['strategy','totalTrades','winRate','netPnL','profitFactor','maxDrawdown','avgPnL']:
    if k in d: print(f'  {k}: {d[k]}')
if 'error' in d: print(f'  ERROR: {d[\"error\"]}')
"
