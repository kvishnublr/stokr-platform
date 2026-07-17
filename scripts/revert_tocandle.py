#!/usr/bin/env python3
FILE = '/opt/stokr/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/engine/BacktestController.java'
with open(FILE, 'r') as f:
    c = f.read()

# Revert the toCandle midnight fix — show real timestamps, let frontend handle display
old = '''    private Candle toCandle(CandleData cd) {
        java.time.LocalDateTime ts = cd.getTimestamp();
        // Daily candles from DB have midnight timestamps — set to 15:15 IST (market close)
        if (ts != null && ts.getHour() == 0 && ts.getMinute() == 0) {
            ts = ts.withHour(15).withMinute(15);
        }
        return new Candle(
            cd.getSymbol(),
            ts,
            cd.getOpen(), cd.getHigh(), cd.getLow(), cd.getClose(), cd.getVolume()
        );
    }'''

new = '''    private Candle toCandle(CandleData cd) {
        java.time.LocalDateTime ts = cd.getTimestamp();
        return new Candle(
            cd.getSymbol(),
            ts,
            cd.getOpen(), cd.getHigh(), cd.getLow(), cd.getClose(), cd.getVolume()
        );
    }'''

if old in c:
    c = c.replace(old, new)
    with open(FILE, 'w') as f:
        f.write(c)
    print('Reverted toCandle to original')
else:
    print('Already reverted or pattern not found')
