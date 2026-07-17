import paramiko,time
s=paramiko.SSHClient();s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84',username='root',password='***',timeout=15)

script = '''import psycopg2,copy,math,datetime,collections

conn = psycopg2.connect("dbname=stokr_lite user=postgres password=root123 host=localhost")
cur = conn.cursor()

# Get all symbols with 1-min data
cur.execute("SELECT DISTINCT symbol FROM candle_data WHERE timeframe='1min'")
symbols = [r[0] for r in cur.fetchall()]
print(f"Symbols: {len(symbols)}")

# Load 1-min candle data for last 3 months into memory by symbol
three_mo = datetime.datetime.now() - datetime.timedelta(days=90)
Trade = collections.namedtuple('Trade', 'symbol entry exit pnl entry_time exit_type')
all_trades = []

for si, sym in enumerate(symbols):
    cur.execute("""
        SELECT timestamp, open, high, low, close, volume 
        FROM candle_data 
        WHERE symbol=%s AND timeframe='1min' AND timestamp >= %s
        ORDER BY timestamp
    """, (sym, three_mo))
    rows = cur.fetchall()
    if len(rows) < 300: continue
    
    # Group by date
    by_date = {}
    for r in rows:
        d = r[0].date()
        by_date.setdefault(d, []).append(r)
    
    for date, candles in by_date.items():
        n = len(candles)
        if n < 40: continue
        
        # Walk candles looking for institutional footprint signal
        for i in range(40, n, 5):  # every 5 minutes
            window = candles[:i+1]
            cn = len(window)
            
            # Compute VWAP
            pv_sum = vol_sum = 0.0
            for c in window:
                tp = (c[1] + c[2] + c[4]) / 3.0  # (H+L+C)/3
                v = c[5]
                pv_sum += tp * v; vol_sum += v
            vwap = pv_sum / vol_sum if vol_sum > 0 else window[-1][4]
            
            latest = window[-1]
            entry_px = latest[4]
            if entry_px < 80 or entry_px > 5000: continue
            
            t = latest[0]
            total_min = t.hour * 60 + t.minute
            if total_min < 9*60+45 or total_min > 14*60+45: continue
            if total_min >= 11*60+30 and total_min < 13*60: continue
            
            # Score 1: VSA (0-30) - simplified
            lookback = min(20, cn-1)
            avg_vol = sum(window[j][5] for j in range(cn-lookback-1, cn-1)) / max(1,lookback)
            avg_spread = sum((window[j][2]-window[j][3])/window[j][4]*100 for j in range(cn-lookback-1, cn-1)) / max(1,lookback)
            
            vol_ratio = latest[5] / max(1,avg_vol)
            spread_pct = (latest[2] - latest[3]) / latest[4] * 100
            is_green = latest[4] > latest[1]
            
            session_low = min(window[j][3] for j in range(max(0,cn-40), cn))
            dist_low = (entry_px - session_low) / session_low * 100
            
            vsa_score = 0
            if vol_ratio < 0.6 and spread_pct < 0.3 and dist_low < 0.5: vsa_score = 30
            elif vol_ratio > 2.5 and spread_pct > 1.0 and is_green: vsa_score = 25
            elif vol_ratio > 2.0 and spread_pct < 1.0 and dist_low < 1.0: vsa_score = 20
            elif vol_ratio > 1.3 and is_green and spread_pct > avg_spread*0.8: vsa_score = 15
            
            # Score 3: Order flow (0-25)
            up_vol = sum(window[j][5] for j in range(cn-lookback, cn) if window[j][4] > window[j][1])
            dn_vol = sum(window[j][5] for j in range(cn-lookback, cn) if window[j][4] < window[j][1])
            of_ratio = up_vol / max(1, dn_vol)
            of_score = 10 if of_ratio >= 2.0 else (6 if of_ratio >= 1.5 else (3 if of_ratio >= 1.0 else 0))
            
            # Score 4: Setup (0-20)
            setup = 0
            vwap_dist = (entry_px - vwap) / vwap * 100
            if 0 <= vwap_dist <= 0.3: setup += 8
            elif 0 <= vwap_dist <= 0.6: setup += 6
            elif 0 <= vwap_dist <= 1.0: setup += 4
            elif 0 <= vwap_dist: setup += 2
            
            session_high = max(window[j][2] for j in range(max(0,cn-40), cn))
            session_range = session_high - session_low
            if session_range > 0:
                midpoint = (session_high + session_low) / 2
                if entry_px > midpoint: setup += 3
            
            # Day change check
            day_open = window[0][1] if len(window) >= 40 else window[0][1]
            day_chg = (entry_px - day_open) / day_open * 100
            if day_chg > 3.0: setup -= 5
            if day_chg < -2.0: setup -= 3
            setup = max(0, min(20, setup))
            
            total = vsa_score + of_score + setup
            if total < 60: continue
            
            # Simulate exit
            atr = sum((window[j][2]-window[j][3]) for j in range(cn-14, cn)) / 14 if cn >= 14 else entry_px*0.005
            sl = entry_px - 2*atr
            target = entry_px + 3*atr
            if (entry_px-sl)/entry_px*100 > 1.2: sl = entry_px*0.988
            if (target-entry_px)/entry_px*100 > 3.5: target = entry_px*1.035
            
            peak = entry_px
            trail = False
            exit_px = entry_px
            ext = 'TIME'
            
            for j in range(i+1, min(i+60, n)):
                c = candles[j]
                if c[2] > peak: peak = c[2]
                if not trail and (peak-entry_px)/entry_px*100 >= 1.2: trail = True
                eff_sl = peak*0.992 if trail else sl
                if c[3] <= eff_sl:
                    exit_px = eff_sl; ext = 'TRAIL' if trail else 'SL'; break
                if c[2] >= target:
                    exit_px = target; ext = 'TARGET'; break
            
            gross = 25000 * (exit_px - entry_px) / entry_px
            net = gross - 40
            all_trades.append(Trade(sym, entry_px, exit_px, net, t, ext))
    
    if (si+1) % 50 == 0:
        print(f"  {si+1}/{len(symbols)} symbols, {len(all_trades)} trades found")

conn.close()

# Stats
if not all_trades:
    print("NO TRADES")
    exit()

wins = [t for t in all_trades if t.pnl > 0]
losses = [t for t in all_trades if t.pnl <= 0]
total_pnl = sum(t.pnl for t in all_trades)
wr = len(wins)/len(all_trades)*100
print(f"\\n=== RESULTS ===")
print(f"Total Trades: {len(all_trades)}")
print(f"Wins: {len(wins)}, Losses: {len(losses)}")
print(f"Win Rate: {wr:.1f}%")
print(f"Total Net PnL: Rs.{total_pnl:,.0f}")
print(f"Monthly PnL: Rs.{total_pnl/3:,.0f}")
print(f"Avg Win: Rs.{sum(t.pnl for t in wins)/max(1,len(wins)):,.0f}")
print(f"Avg Loss: Rs.{sum(t.pnl for t in losses)/max(1,len(losses)):,.0f}")
print(f"Profit Factor: {sum(t.pnl for t in wins)/abs(sum(t.pnl for t in losses)):.2f}" if losses else "")
# Max drawdown
peak = dd = 0
for t in all_trades:
    peak += t.pnl
    if peak > 0: peak = 0
    dd = min(dd, peak)
print(f"Max Drawdown: Rs.{-dd:,.0f}")

# Exit breakdown
from collections import Counter
ec = Counter(t.exit_type for t in all_trades)
print(f"\\nExit Types: {dict(ec)}")

# Top/bottom symbols
by_sym = {}
for t in all_trades:
    by_sym.setdefault(t.symbol, []).append(t.pnl)
top = sorted(by_sym.items(), key=lambda x: sum(x[1]), reverse=True)[:5]
bot = sorted(by_sym.items(), key=lambda x: sum(x[1]))[:5]
print(f"\\nBest symbols: {[(s, int(sum(p))) for s,p in top]}")
print(f"Worst symbols: {[(s, int(sum(p))) for s,p in bot]}")
'''

stdin, stdout, stderr = s.exec_command("python3 -u /dev/stdin", get_pty=True)
stdin.write(script.encode())
stdin.channel.shutdown_write()

import select, sys
while not stdout.channel.exit_status_ready():
    if stdout.channel.recv_ready():
        d = stdout.channel.recv(4096).decode('utf-8',errors='replace')
        if d.strip():
            sys.stdout.write(d)
            sys.stdout.flush()
    time.sleep(0.3)
while stdout.channel.recv_ready():
    d = stdout.channel.recv(4096).decode('utf-8',errors='replace')
    if d.strip(): print(d.strip())

s.close()
