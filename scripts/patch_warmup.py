#!/usr/bin/env python3
FILE = '/opt/stokr/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/engine/BacktestController.java'
with open(FILE, 'r') as f:
    c = f.read()

# 1. Add warmupStartTime after cacheKey line
old1 = '            String cacheKey = computeCacheKey(pluginType, universe, startTime, endTime, brokerage, timeframe);\n            boolean cached = false;'
new1 = '''            String cacheKey = computeCacheKey(pluginType, universe, startTime, endTime, brokerage, timeframe);
            boolean cached = false;

            // For daily strategies, load extra warmup data before startTime
            // EMA50D needs 50 candles, OB needs ~20 — load 100 extra days to be safe
            LocalDateTime warmupStartTime = startTime;
            if (DAILY_STRATEGIES.contains(resolvedPluginType)) {
                warmupStartTime = startTime.minusDays(150);
                log.info("Daily strategy warmup: loading from {} instead of {} (extra 150 days)", warmupStartTime, startTime);
            }'''

if old1 in c:
    c = c.replace(old1, new1)
    print('1. Added warmupStartTime')
else:
    print('1. SKIP - pattern not found (may already be patched)')

# 2. Change fetchCandles to use warmupStartTime
old2 = 'candleFetchService.fetchCandles(symbol, finalTimeframe, startTime, endTime)'
new2 = 'candleFetchService.fetchCandles(symbol, finalTimeframe, warmupStartTime, endTime)'
if old2 in c:
    c = c.replace(old2, new2)
    print('2. Changed fetch to use warmupStartTime')
else:
    print('2. SKIP - pattern not found')

# 3. Add filter after simulateStrategy
old3 = '            allTrades.sort(java.util.Comparator.comparing(t -> t.entryTime));\n\n            int totalTrades = allTrades.size();'
new3 = '''            // Filter out warmup trades — only keep trades from user's actual start date
            final LocalDateTime filterStart = startTime;
            allTrades.removeIf(t -> t.entryTime != null && t.entryTime.isBefore(filterStart));
            allTrades.sort(java.util.Comparator.comparing(t -> t.entryTime));

            int totalTrades = allTrades.size();'''
if old3 in c:
    c = c.replace(old3, new3)
    print('3. Added warmup trade filter')
else:
    print('3. SKIP - pattern not found')

with open(FILE, 'w') as f:
    f.write(c)

# Verify
count = c.count('warmupStartTime')
print(f'\nVerify: warmupStartTime refs = {count}')
