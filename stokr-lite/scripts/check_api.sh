#!/bin/bash
curl -s http://localhost:8081/api/signals --max-time 5 | python3 -c "
import json, sys
d = json.load(sys.stdin)
if d:
    s = d[0]
    for k in ['symbol', 'side', 'strategyName', 'timeframe', 'status']:
        print(f'{k}: {s.get(k)}')
else:
    print('No signals')
"