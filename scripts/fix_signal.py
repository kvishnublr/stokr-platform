#!/usr/bin/env python3
"""Fix SignalProcessor.java compilation errors on server"""
f = "/opt/stokr/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/engine/SignalProcessor.java"
with open(f) as fp:
    code = fp.read()

# Fix 1: Replace c.typicalPrice() with (h+l+c)/3
code = code.replace(
    "c.typicalPrice().multiply(c.volume())",
    "(c.high().add(c.low()).add(c.close())).divide(BigDecimal.valueOf(3)).multiply(BigDecimal.valueOf(c.volume()))"
)

# Fix 2: Fix VWAP volume reduce
code = code.replace(
    "candles.stream().map(Candle::volume).reduce(BigDecimal.ZERO, BigDecimal::add)",
    "BigDecimal.valueOf(candles.stream().mapToLong(Candle::volume).sum())"
)

# Fix 3: Fix volume SMA block
old_vol = """    BigDecimal volSma10 = BigDecimal.ZERO;
                    if (dailyCandles.size() >= 10) {
                        volSma10 = dailyCandles.subList(dailyCandles.size() - 10, dailyCandles.size()).stream()
                            .map(Candle::volume).reduce(BigDecimal.ZERO, BigDecimal::add)
                            .divide(BigDecimal.TEN, 0, RoundingMode.HALF_UP);
                    }"""
new_vol = """    BigDecimal volSma10 = BigDecimal.ZERO;
                    if (dailyCandles.size() >= 10) {
                        long volSum = dailyCandles.subList(dailyCandles.size() - 10, dailyCandles.size()).stream()
                            .mapToLong(Candle::volume).sum();
                        volSma10 = BigDecimal.valueOf(volSum).divide(BigDecimal.TEN, 0, RoundingMode.HALF_UP);
                    }"""
code = code.replace(old_vol, new_vol)

with open(f, 'w') as fp:
    fp.write(code)

print("SignalProcessor.java fixed")
