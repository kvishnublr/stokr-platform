"""Quick diagnostic - show min/max/avg of VSA metrics."""
import psycopg2, datetime

conn = psycopg2.connect("dbname=stokr_lite user=postgres host=/var/run/postgresql")
cur = conn.cursor()

cur.execute("SELECT DISTINCT symbol FROM candle_data WHERE timeframe='1min' LIMIT 20")
symbols = [r[0] for r in cur.fetchall()]

three_mo = datetime.datetime.now() - datetime.timedelta(days=30)
vol_ratios, spreads, dist_lows = [], [], []

for sym in symbols:
    cur.execute("""SELECT timestamp, open, high, low, close, volume
        FROM candle_data WHERE symbol=%s AND timeframe='1min' AND timestamp >= %s
        ORDER BY timestamp""", (sym, three_mo))
    rows = [(r[0], float(r[1]), float(r[2]), float(r[3]), float(r[4]), int(r[5])) for r in cur.fetchall()]
    if len(rows) < 300: continue

    by_date = {}
    for r in rows:
        by_date.setdefault(r[0].date(), []).append(r)

    for date, candles in by_date.items():
        n = len(candles)
        if n < 40: continue

        for i in range(40, n, 20):
            window = candles[:i + 1]
            cn = len(window)
            latest = window[-1]
            entry_px = latest[4]
            if entry_px < 80: continue

            lb = min(20, cn - 1)
            avg_vol = sum(window[j][5] for j in range(cn - lb - 1, cn - 1)) / max(1, lb)
            if avg_vol <= 0: continue

            vol_ratio = latest[5] / avg_vol
            spread_pct = (latest[2] - latest[3]) / latest[4] * 100
            session_low = min(window[j][3] for j in range(max(0, cn - 40), cn))
            if session_low <= 0: continue
            dist_low = (entry_px - session_low) / session_low * 100

            vol_ratios.append(vol_ratio)
            spreads.append(spread_pct)
            dist_lows.append(dist_low)

conn.close()

import statistics
print(f"Samples: {len(vol_ratios)}")
print(f"vol_ratio:  min={min(vol_ratios):.2f} max={max(vol_ratios):.2f} med={statistics.median(vol_ratios):.2f} avg={sum(vol_ratios)/len(vol_ratios):.2f}")
print(f"spread_pct: min={min(spreads):.2f} max={max(spreads):.2f} med={statistics.median(spreads):.2f} avg={sum(spreads)/len(spreads):.2f}")
print(f"dist_low:   min={min(dist_lows):.2f} max={max(dist_lows):.2f} med={statistics.median(dist_lows):.2f} avg={sum(dist_lows)/len(dist_lows):.2f}")

# How many pass each VSA threshold?
for thresh, label in [(0.6, 'vol<0.6+spread<0.3'), (2.5, 'vol>2.5+spread>1.0'), (2.0, 'vol>2.0'), (1.3, 'vol>1.3+green')]:
    if 'vol<' in label:
        count = sum(1 for v,s in zip(vol_ratios,spreads) if v < thresh and s < 0.3)
    elif 'vol>2.5' in label:
        count = sum(1 for v,s in zip(vol_ratios,spreads) if v > 2.5 and s > 1.0)
    elif 'vol>2.0' in label:
        count = sum(1 for v in vol_ratios if v > 2.0)
    else:
        count = sum(1 for v in vol_ratios if v > 1.3)
    print(f"  {label}: {count} ({count/len(vol_ratios)*100:.1f}%)")
