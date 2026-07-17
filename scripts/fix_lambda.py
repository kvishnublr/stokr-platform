#!/usr/bin/env python3
FILE = '/opt/stokr/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/engine/BacktestController.java'
with open(FILE, 'r') as f:
    c = f.read()

# Fix: add a final copy of warmupStartTime before the lambda
old = '            final String finalTimeframe = timeframe;'
new = '            final String finalTimeframe = timeframe;\n            final LocalDateTime fetchStart = warmupStartTime;'

if old in c:
    c = c.replace(old, new)
    print('Added final fetchStart')
else:
    print('Pattern not found')

# Replace warmupStartTime inside lambda with fetchStart
old2 = 'candleFetchService.fetchCandles(symbol, finalTimeframe, warmupStartTime, endTime)'
new2 = 'candleFetchService.fetchCandles(symbol, finalTimeframe, fetchStart, endTime)'
if old2 in c:
    c = c.replace(old2, new2)
    print('Fixed lambda reference')
else:
    print('Lambda pattern not found')

with open(FILE, 'w') as f:
    f.write(c)
