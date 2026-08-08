#!/bin/bash
echo "=== Iron Condor ==="
curl -s "http://127.0.0.1:8081/api/option-arbitrage/iron-condor/scan?underlying=NIFTY" -H "Authorization: Bearer test" | python3 -c "import sys,json; d=json.load(sys.stdin); print('opps:', len(d.get('opportunities',[])), 'error:', d.get('error','none'))"

echo "=== Cash Surge ==="
curl -s "http://127.0.0.1:8081/api/option-arbitrage/cash-surge/scan" -H "Authorization: Bearer test" | python3 -c "import sys,json; d=json.load(sys.stdin); print('opps:', len(d.get('opportunities',[])), 'error:', d.get('error','none'))"

echo "=== Cash Swing ==="
curl -s "http://127.0.0.1:8081/api/option-arbitrage/cash-momentum/scan" -H "Authorization: Bearer test" | python3 -c "import sys,json; d=json.load(sys.stdin); print('opps:', len(d.get('opportunities',[])), 'error:', d.get('error','none'))"

echo "=== Calendar ==="
curl -s "http://127.0.0.1:8081/api/option-arbitrage/calendar/scan?underlying=NIFTY" -H "Authorization: Bearer test" | python3 -c "import sys,json; d=json.load(sys.stdin); print('opps:', len(d.get('opportunities',[])), 'error:', d.get('error','none'))"

echo "=== Box Spread ==="
curl -s "http://127.0.0.1:8081/api/option-arbitrage/box-spread/scan?underlying=NIFTY" -H "Authorization: Bearer test" | python3 -c "import sys,json; d=json.load(sys.stdin); print('opps:', len(d.get('opportunities',[])), 'error:', d.get('error','none'))"
