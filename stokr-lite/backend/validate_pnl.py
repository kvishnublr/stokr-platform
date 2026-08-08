import json, urllib.request

resp = urllib.request.urlopen('http://127.0.0.1:8081/api/option-arbitrage/live-positions')
data = json.loads(resp.read())
print('Total PnL:', data.get('totalPnl'))
print()

for p in data.get('positions', []):
    act = (p.get('action') or '').upper()
    ceE = float(p.get('ceEntryPrice') or 0)
    peE = float(p.get('peEntryPrice') or 0)
    futE = float(p.get('futEntryPrice') or 0)
    ceC = float(p.get('ceCurrent') or 0)
    peC = float(p.get('peCurrent') or 0)
    futC = float(p.get('futCurrent') or 0)
    ls = int(p.get('lotSize') or 25)
    lots = int(p.get('lots') or 1)
    reported = float(p.get('currentPnl') or 0)
    target = float(p.get('targetEdge') or 0)
    edgeCap = p.get('edgeCaptured')

    if 'SELL CE+PE' in act:
        pnlCE = ceE - ceC
        pnlPE = peE - peC
        pnlFUT = futC - futE
        calc = (pnlCE + pnlPE + pnlFUT) * ls * lots
    elif 'BUY CE+PE' in act:
        pnlCE = ceC - ceE
        pnlPE = peC - peE
        pnlFUT = futE - futC
        calc = (pnlCE + pnlPE + pnlFUT) * ls * lots
    else:
        pnlCE = pnlPE = pnlFUT = 0
        calc = 0

    match = 'OK' if abs(reported - calc) < 2 else 'MISMATCH'
    print(f'ID:{p.get("id")} {p.get("underlying")} {p.get("strike")} {act[:30]}')
    print(f'  CE:  entry={ceE:>8} current={ceC:>8} pnl/lot={pnlCE:>8.1f}')
    print(f'  PE:  entry={peE:>8} current={peC:>8} pnl/lot={pnlPE:>8.1f}')
    print(f'  FUT: entry={futE:>8} current={futC:>8} pnl/lot={pnlFUT:>8.1f}')
    print(f'  Sum/lot={pnlCE+pnlPE+pnlFUT:.1f} x lotSize={ls} x lots={lots} = calc={calc:.0f} reported={reported} [{match}]')
    print(f'  target={target} edgeCaptured={edgeCap}%')
    print()
