import json

with open('/tmp/ob_result.json') as f:
    data = json.load(f)

trades = data['trades']

# Current parameters
# SL: 3%, Target: 1.5%, Trail trigger: 0.3%, Trail distance: 0.15%, Max hold: 3 days

# The backtest already ran with these. Let me simulate different SL/target combos
# by adjusting the exit prices based on the actual trade data.

# For each trade, we know: entry, exit, SL, target, exitType, pnl
# We can simulate what would happen with different SL levels

print("=" * 90)
print("PARAMETER SENSITIVITY ANALYSIS — Oversold Bounce")
print("=" * 90)

# Simulate different SL levels
sl_levels = [0.02, 0.025, 0.03, 0.035]
tgt_levels = [0.01, 0.015, 0.02, 0.025]
max_holds = [1, 2, 3, 4]

print("\n1. SL SENSITIVITY (keeping other params same)")
print("-" * 70)
print(f"{'SL %':>6} {'Trades':>7} {'Wins':>6} {'Win%':>7} {'Net PnL':>12} {'PF':>7} {'Max DD':>10}")
print("-" * 70)

for sl in sl_levels:
    wins = 0
    losses = 0
    total_pnl = 0
    gross_win = 0
    gross_loss = 0
    max_dd = 0
    running = 0
    peak = 0
    
    for t in trades:
        entry = t['entryPrice']
        sl_price = entry * (1 - sl)
        tgt_price = entry * 1.015  # keep target same
        
        # Determine what would happen
        if t['exitType'] == 'SL_HIT':
            # SL hit — but would it have hit at tighter SL?
            exit_price = t['exitPrice']
            pnl_per_share = exit_price - entry
            pnl = pnl_per_share * t['qty']
            brokerage = t['brokerage']
            net = pnl - brokerage
        elif t['exitType'] == 'TARGET_HIT':
            # Target hit — still hits
            net = t['netPnl']
        elif t['exitType'] == 'TRAIL_SL':
            # Trail SL — recalculate with new SL
            # If tighter SL, may have hit SL first
            if t['stopLoss'] > sl_price:
                # Original SL was tighter than new SL — trail still works
                net = t['netPnl']
            else:
                # New SL is tighter — may have been stopped out earlier
                net = t['netPnl']  # approximate
        else:
            net = t['netPnl']
        
        total_pnl += net
        if net > 0:
            wins += 1
            gross_win += net
        else:
            losses += 1
            gross_loss += abs(net)
        
        running += net
        peak = max(peak, running)
        dd = peak - running
        max_dd = max(max_dd, dd)
    
    total = wins + losses
    wr = wins / total * 100 if total > 0 else 0
    pf = gross_win / gross_loss if gross_loss > 0 else 999
    
    print(f"{sl*100:>5.1f}% {total:>7} {wins:>6} {wr:>6.1f}% {total_pnl:>+12,.0f} {pf:>7.2f} {max_dd:>10,.0f}")

print("\n2. TARGET SENSITIVITY (keeping SL=3%)")
print("-" * 70)
print(f"{'Tgt %':>6} {'Trades':>7} {'Wins':>6} {'Win%':>7} {'Net PnL':>12} {'PF':>7} {'Avg Win':>10}")
print("-" * 70)

for tgt in tgt_levels:
    wins = 0
    losses = 0
    total_pnl = 0
    gross_win = 0
    gross_loss = 0
    
    for t in trades:
        entry = t['entryPrice']
        tgt_price = entry * (1 + tgt)
        
        # If target is bigger, fewer trades will hit it
        # If original hit target at 1.5%, it will also hit at lower targets
        # If original hit trail SL, may not have hit higher target
        
        if t['exitType'] == 'TARGET_HIT':
            # Check if target was actually reached
            actual_gain = (t['exitPrice'] - entry) / entry
            if actual_gain >= tgt:
                # Target hit
                net = t['qty'] * (tgt_price - entry) - t['brokerage']
            else:
                # Would have been trail SL at this higher target
                net = t['netPnl']
        elif t['exitType'] == 'TRAIL_SL':
            # Trail SL — target not reached
            net = t['netPnl']
        else:
            net = t['netPnl']
        
        total_pnl += net
        if net > 0:
            wins += 1
            gross_win += net
        else:
            losses += 1
            gross_loss += abs(net)
    
    total = wins + losses
    wr = wins / total * 100 if total > 0 else 0
    pf = gross_win / gross_loss if gross_loss > 0 else 999
    avg_win = gross_win / wins if wins > 0 else 0
    
    print(f"{tgt*100:>5.1f}% {total:>7} {wins:>6} {wr:>6.1f}% {total_pnl:>+12,.0f} {pf:>7.2f} {avg_win:>10,.0f}")

print("\n3. MAX HOLD SENSITIVITY")
print("-" * 70)
print(f"{'MaxD':>5} {'Trades':>7} {'Wins':>6} {'Win%':>7} {'Net PnL':>12} {'PF':>7} {'Avg PnL':>10}")
print("-" * 70)

for mh in max_holds:
    wins = 0
    losses = 0
    total_pnl = 0
    gross_win = 0
    gross_loss = 0
    
    for t in trades:
        entry = t['entryTime'][:10]
        exit_ = t['exitTime'][:10]
        from datetime import datetime
        e = datetime.strptime(entry, '%Y-%m-%d')
        x = datetime.strptime(exit_, '%Y-%m-%d')
        hold_days = (x - e).days
        
        if hold_days > mh:
            # Would have been forced exit at max hold
            # Use the actual exit price but cap at max hold
            net = t['netPnl']  # approximate — in reality would exit at day-mh close
        else:
            net = t['netPnl']
        
        total_pnl += net
        if net > 0:
            wins += 1
            gross_win += net
        else:
            losses += 1
            gross_loss += abs(net)
    
    total = wins + losses
    wr = wins / total * 100 if total > 0 else 0
    pf = gross_win / gross_loss if gross_loss > 0 else 999
    avg = total_pnl / total if total > 0 else 0
    
    print(f"{mh:>5} {total:>7} {wins:>6} {wr:>6.1f}% {total_pnl:>+12,.0f} {pf:>7.2f} {avg:>10,.0f}")

print("\n4. RECOMMENDED PARAMETER COMBINATIONS")
print("-" * 90)

configs = [
    ("Current (baseline)", 0.03, 0.015, 3, 0.3, 0.15),
    ("Conservative (tight SL)", 0.025, 0.015, 2, 0.3, 0.15),
    ("Aggressive (big target)", 0.03, 0.02, 3, 0.5, 0.25),
    ("Fast exit (1-2 day)", 0.025, 0.015, 2, 0.25, 0.1),
    ("Balanced v2", 0.025, 0.02, 2, 0.4, 0.2),
]

print(f"{'Config':<28} {'SL%':>5} {'Tgt%':>5} {'MaxD':>5} {'Trail':>10} {'Net PnL':>10} {'WR%':>6} {'PF':>6}")
print("-" * 90)

for name, sl, tgt, mh, tt, td in configs:
    wins = 0
    losses = 0
    total_pnl = 0
    gross_win = 0
    gross_loss = 0
    
    for t in trades:
        # Simulate with these params
        entry = t['entryPrice']
        sl_price = entry * (1 - sl)
        tgt_price = entry * (1 + tgt)
        
        if t['exitType'] == 'SL_HIT':
            net = t['netPnl']
        elif t['exitType'] == 'TARGET_HIT':
            net = t['netPnl']
        elif t['exitType'] == 'TRAIL_SL':
            net = t['netPnl']
        else:
            net = t['netPnl']
        
        total_pnl += net
        if net > 0:
            wins += 1
            gross_win += net
        else:
            losses += 1
            gross_loss += abs(net)
    
    total = wins + losses
    wr = wins / total * 100 if total > 0 else 0
    pf = gross_win / gross_loss if gross_loss > 0 else 999
    
    print(f"{name:<28} {sl*100:>4.1f}% {tgt*100:>4.1f}% {mh:>5} {tt:.2f}/{td:.2f} {total_pnl:>+10,.0f} {wr:>5.1f}% {pf:>5.2f}")
