#!/bin/bash
# Verify top strategies via the actual backtest API
API="http://localhost:8081/api/backtest/advanced"

echo "=== EMA50_DISTANCE (if it exists as plugin) ==="
curl -s -X POST "${API}?strategy=EMA50_DISTANCE&universe=NIFTY_50&dateStart=2025-07-01&dateEnd=2026-07-07&capital=100000" | python3 -c "
import sys, json
d = json.load(sys.stdin)
for k in ['strategy','totalTrades','winRate','netPnL','profitFactor','maxDrawdown','avgPnL']:
    if k in d: print(f'  {k}: {d[k]}')
"
echo ""

# The Python script tests raw candle logic. Let me also verify OB baseline with the swing endpoint
echo "=== OB via swing endpoint (ground truth) ==="
curl -s -X POST "http://localhost:8081/api/backtest/swing?strategy=OVERSOLD_BOUNCE&universe=NIFTY_50&dateStart=2025-07-01&dateEnd=2026-07-07&capital=100000" | python3 -c "
import sys, json
d = json.load(sys.stdin)
for k in ['strategy','totalTrades','winRate','netPnL','profitFactor','maxDrawdown']:
    if k in d: print(f'  {k}: {d[k]}')
"
echo ""

# Check if RSI_OVERSOLD or EMA50_DISTANCE plugins exist
echo "=== Check plugin list ==="
curl -s "http://localhost:8081/api/strategies" | python3 -c "
import sys, json
data = json.load(sys.stdin)
for s in data:
    print(f'  {s.get(\"strategyType\",\"?\")} - {s.get(\"name\",\"?\")}')
"
