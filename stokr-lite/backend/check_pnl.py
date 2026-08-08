import json

with open('/tmp/pos.json') as f:
    data = json.load(f)

for p in data.get('positions', []):
    if p.get('underlying') == 'MIDCPNIFTY':
        ceE = p.get('ceEntryPrice', 0) or 0
        peE = p.get('peEntryPrice', 0) or 0
        futE = p.get('futEntryPrice', 0) or 0
        ceC = p.get('ceCurrent', 0) or 0
        peC = p.get('peCurrent', 0) or 0
        futC = p.get('futCurrent', 0) or 0
        lots = p.get('lots', 1) or 1
        lotSize = p.get('lotSize', 50) or 50
        target = p.get('targetEdge', 0) or 0
        status = p.get('status', '')
        action = p.get('action', '')

        futPnl = (futC - futE) * lots * lotSize
        cePnl = (ceE - ceC) * lots * lotSize
        pePnl = (peE - peC) * lots * lotSize
        total = futPnl + cePnl + pePnl

        uid = p['id']
        und = p['underlying']
        stk = p['strike']
        api_pnl = p.get('currentPnl', 0) or 0
        diff = total - api_pnl

        print(f"ID={uid} {und} {stk} {action}")
        print(f"  Entry: CE={ceE} PE={peE} FUT={futE}")
        print(f"  Curr:  CE={ceC} PE={peC} FUT={futC}")
        print(f"  PnL: FUT={futPnl:.0f} CE={cePnl:.0f} PE={pePnl:.0f} TOTAL={total:.0f}")
        print(f"  Target={target} API_pnl={api_pnl} Diff={diff:.0f}")
        print()
