import subprocess, json

def remote(cmd):
    r = subprocess.run([
        "ssh", "-o", "ConnectTimeout=30", "-o", "StrictHostKeyChecking=no",
        "root@173.249.55.84", cmd
    ], capture_output=True, text=True, timeout=120)
    return r.stdout + r.stderr

strategies = [
    ("OVERSOLD_BOUNCE", "OB"),
    ("EMA50_DISTANCE", "EMA50D"),
    ("THREE_RED_DAYS", "TRD"),
    ("RSI_OVERSOLD", "RSI"),
]

for strat_type, label in strategies:
    print(f"\n{'='*60}")
    print(f"=== {label} ({strat_type}) ===")
    print(f"{'='*60}")
    
    result = remote(f"""curl -s -X POST 'http://localhost:8081/api/backtest/advanced' -H 'Content-Type: application/x-www-form-urlencoded' -d 'strategy={strat_type}&symbolGroup=NIFTY_100&dateStart=2026-07-10&dateEnd=2026-07-13&capital=25000'""")
    
    try:
        data = json.loads(result)
        trades = data.get('trades', [])
        summary = data.get('summary', {})
        
        print(f"Total trades: {len(trades)}")
        print(f"Win rate: {summary.get('winRate', 'N/A')}")
        print(f"Total P&L: {summary.get('totalPnL', 'N/A')}")
        
        # Jul 13 signals
        jul13 = [t for t in trades if '2026-07-13' in str(t.get('entryTime', ''))]
        print(f"\nJul 13 signals: {len(jul13)}")
        for t in jul13:
            print(f"  {t.get('symbol',''):15} | {t.get('side',''):5} | Entry: {t.get('entryPrice',''):>10} | SL: {t.get('stopLoss',''):>10} | Tgt: {t.get('target',''):>10} | Score: {t.get('confidence','')}")
        
        # Jul 10 signals
        jul10 = [t for t in trades if '2026-07-10' in str(t.get('entryTime', ''))]
        print(f"\nJul 10 signals: {len(jul10)}")
        for t in jul10:
            print(f"  {t.get('symbol',''):15} | {t.get('side',''):5} | Entry: {t.get('entryPrice',''):>10} | SL: {t.get('stopLoss',''):>10} | Tgt: {t.get('target',''):>10} | Score: {t.get('confidence','')}")
        
        # Jul 9 signals
        jul9 = [t for t in trades if '2026-07-09' in str(t.get('entryTime', ''))]
        print(f"\nJul 9 signals: {len(jul9)}")
        for t in jul9:
            print(f"  {t.get('symbol',''):15} | {t.get('side',''):5} | Entry: {t.get('entryPrice',''):>10} | SL: {t.get('stopLoss',''):>10} | Tgt: {t.get('target',''):>10} | Score: {t.get('confidence','')}")
            
    except Exception as e:
        print(f"Error: {e}")
        print(result[:300])
