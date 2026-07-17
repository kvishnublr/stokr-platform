#!/usr/bin/env python3
FILE = '/opt/stokr/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/engine/BacktestController.java'
with open(FILE, 'r') as f:
    c = f.read()
old = '    private Candle toCandle(CandleData cd) {\n        return new Candle(\n            cd.getSymbol(),\n            cd.getTimestamp(),  // already LocalDateTime (IST)\n            cd.getOpen(), cd.getHigh(), cd.getLow(), cd.getClose(), cd.getVolume()\n        );\n    }'
new = '    private Candle toCandle(CandleData cd) {\n        java.time.LocalDateTime ts = cd.getTimestamp();\n        if (ts != null && ts.getHour() == 0 && ts.getMinute() == 0) {\n            ts = ts.withHour(15).withMinute(15);\n        }\n        return new Candle(\n            cd.getSymbol(),\n            ts,\n            cd.getOpen(), cd.getHigh(), cd.getLow(), cd.getClose(), cd.getVolume()\n        );\n    }'
if old in c:
    c = c.replace(old, new)
    with open(FILE, 'w') as f:
        f.write(c)
    print('Patched toCandle')
elif 'withHour(15)' in c:
    print('Already patched')
else:
    print('Pattern not found')
