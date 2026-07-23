curl -sk 'http://localhost:8081/api/option-arbitrage/scan?underlying=ALL&force=true' 2>/dev/null | python3 -c "
import sys,json
d=json.load(sys.stdin)
print(f'Count: {d[\"count\"]}')
for o in d.get('opportunities',[])[:8]:
    print(f'  {o[\"underlying\"]} {o[\"strike\"]} {o[\"action\"]} edge=Rs.{o[\"edgeAfterCosts\"]:.0f} ({o[\"type\"]})')
"