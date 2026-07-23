#!/bin/bash
curl -sk https://stokr.in/api/option-arbitrage/history 2>/dev/null | python3 -c "
import sys, json
d = json.load(sys.stdin)
opps = d.get('opportunities', [])
print(f'Total opportunities: {len(opps)}')
if opps:
    o = opps[0]
    print(json.dumps({k: o.get(k) for k in ['id','underlying','strike','action','status','strategyType','pnlAfterCosts','pnlAmount','ceSymbol','peSymbol','futSymbol']}, indent=2))
"
