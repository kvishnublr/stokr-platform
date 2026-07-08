#!/bin/bash
# Show actual trade log for EMA50_DISTANCE to verify real data
RESULT=$(curl -s -X POST "http://localhost:8081/api/backtest/advanced?strategy=EMA50_DISTANCE&dateStart=2025-07-08T00:00:00&dateEnd=2026-07-08T23:59:59&universe=NIFTY_50&timeframe=daily&capital=100000")
echo "$RESULT" | python3 -c "
import sys,json
d=json.load(sys.stdin)
trades = d.get('trades', [])
print(f'Total trades: {len(trades)}')
print(f'Net PnL: {d.get(\"netPnL\")}')
print()
print('First 10 trades:')
print(f'{\"Date\":<12} {\"Symbol\":<12} {\"Entry\":<10} {\"Exit\":<10} {\"PnL\":<10} {\"Exit Type\":<15}')
print('-' * 70)
for t in trades[:10]:
    entry_time = t.get('entryTime', '')[:10]
    symbol = t.get('symbol', '')
    entry = t.get('entryPrice', 0)
    exit_price = t.get('exitPrice', 0)
    pnl = t.get('netPnl', t.get('pnl', 0))
    exit_type = t.get('exitType', '')
    print(f'{entry_time:<12} {symbol:<12} {entry:<10.2f} {exit_price:<10.2f} {pnl:<10.0f} {exit_type:<15}')
"
