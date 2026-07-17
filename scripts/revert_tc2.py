#!/usr/bin/env python3
FILE = '/opt/stokr/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/engine/BacktestController.java'
with open(FILE, 'r') as f:
    c = f.read()

old = """    private Candle toCandle(CandleData cd) {
        java.time.LocalDateTime ts = cd.getTimestamp();
        if (ts != null && ts.getHour() == 0 && ts.getMinute() == 0) {
            ts = ts.withHour(15).withMinute(15);
        }
        return new Candle(
            cd.getSymbol(),
            ts,
            cd.getOpen(), cd.getHigh(), cd.getLow(), cd.getClose(), cd.getVolume()
        );
    }"""

new = """    private Candle toCandle(CandleData cd) {
        return new Candle(
            cd.getSymbol(),
            cd.getTimestamp(),
            cd.getOpen(), cd.getHigh(), cd.getLow(), cd.getClose(), cd.getVolume()
        );
    }"""

if old in c:
    c = c.replace(old, new)
    with open(FILE, 'w') as f:
        f.write(c)
    print('Reverted toCandle')
elif 'withHour(15)' in c:
    print('Still has withHour but pattern mismatch - checking...')
    # Try with different spacing
    import re
    c2 = re.sub(r'    private Candle toCandle\(CandleData cd\) \{.*?\n    \}', new.strip(), c, flags=re.DOTALL)
    if c2 != c:
        with open(FILE, 'w') as f:
            f.write(c2)
        print('Reverted via regex')
    else:
        print('Could not revert')
else:
    print('Already clean')
