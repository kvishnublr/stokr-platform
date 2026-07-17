import subprocess, json

def remote(cmd):
    r = subprocess.run([
        "ssh", "-o", "ConnectTimeout=30", "-o", "StrictHostKeyChecking=no",
        "root@173.249.55.84", cmd
    ], capture_output=True, text=True, timeout=120)
    return r.stdout + r.stderr

# Run backtest for all 4 strategies on NIFTY_100 with recent data
strategies = [
    ("OVERSOLD_BOUNCE", "OB"),
    ("EMA50_DISTANCE", "EMA50D"),
    ("THREE_RED_DAYS", "TRD"),
    ("RSI_OVERSOLD", "RSI"),
]

for strat_type, label in strategies:
    print(f"\n{'='*60}")
    print(f"=== BACKTEST: {label} ({strat_type}) ===")
    print(f"{'='*60}")
    
    # Use curl with form data (backtest API uses @RequestParam)
    cmd = f"""curl -s -X POST 'http://localhost:8081/api/backtest/advanced' \
        -H 'Content-Type: application/x-www-form-urlencoded' \
        -d 'strategy={strat_type}&symbolGroup=NIFTY_100&dateStart=2026-07-01&dateEnd=2026-07-13&capital=25000'"""
    
    result = remote(cmd)
    
    try:
        data = json.loads(result)
        summary = data.get('summary', {})
        trades = data.get('trades', [])
        
        print(f"Total trades: {summary.get('totalTrades', 0)}")
        print(f"Win rate: {summary.get('winRate', 0)}")
        print(f"Total P&L: {summary.get('totalPnL', 0)}")
        print(f"Profit factor: {summary.get('profitFactor', 'N/A')}")
        
        if trades:
            print(f"\nFirst 5 trades:")
            for t in trades[:5]:
                print(f"  {t.get('symbol','')} | {t.get('side','')} | Entry: {t.get('entryPrice','')} | Exit: {t.get('exitPrice','')} | P&L: {t.get('pnl','')} | {t.get('exitType','')} | {t.get('entryTime','')[:10] if t.get('entryTime') else ''}")
            
            # Show signals generated on Jul 13
            jul13_trades = [t for t in trades if t.get('entryTime','')[:10] == '2026-07-13']
            print(f"\nJul 13 signals: {len(jul13_trades)}")
            for t in jul13_trades:
                print(f"  {t.get('symbol','')} | {t.get('side','')} | Entry: {t.get('entryPrice','')} | SL: {t.get('stopLoss','')} | Tgt: {t.get('target','')}")
        else:
            print("No trades generated")
    except Exception as e:
        print(f"Parse error: {e}")
        print(f"Raw (first 500): {result[:500]}")
