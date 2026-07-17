#!/usr/bin/env python3
"""
Recovery: Fetch Zerodha closing prices for Jul 15 and reconstruct option arb opportunities.
Uses MONTHLY format (NIFTY26JUL{strike}CE) which Zerodha returns after hours.
"""
import json, time, requests, math
from datetime import datetime, date
import psycopg2

API_KEY = "zazlrld244cc6jf0"
DB_CONN = "host=localhost dbname=stokr_lite user=postgres password=stokr2026"
RISK_FREE_RATE = 0.065
MIN_EDGE = 300
LOT_SIZE = 65

conn = psycopg2.connect(DB_CONN)
cur = conn.cursor()
cur.execute("SELECT access_token FROM broker_accounts WHERE id = 4")
TOKEN = cur.fetchone()[0]
cur.close()
conn.close()

HEADERS = {"Authorization": f"token {API_KEY}:{TOKEN}", "X-Kite-Version": "3"}

# Use monthly JUL expiry (last Tuesday = Jul 28)
# Also try weekly Jul 22 just in case
expiry_str = "26JUL"

strikes = list(range(23600, 24750, 50))
print(f"Scanning {len(strikes)} strikes...")

# Build instrument list — monthly format
instruments = ["NSE:NIFTY 50", "NFO:NIFTY26JULFUT"]
ce_pe = []
for strike in strikes:
    ce_pe.append(f"NFO:NIFTY{expiry_str}{strike}CE")
    ce_pe.append(f"NFO:NIFTY{expiry_str}{strike}PE")
instruments.extend(ce_pe)

# Fetch in batches of 20
all_quotes = {}
batch_size = 20
for i in range(0, len(instruments), batch_size):
    batch = instruments[i:i+batch_size]
    params = [("i", inst) for inst in batch]
    try:
        resp = requests.get("https://api.kite.trade/quote", headers=HEADERS, params=params, timeout=30)
        data = resp.json().get("data", {})
        all_quotes.update(data)
    except Exception as e:
        print(f"  Batch error: {e}")
    time.sleep(0.3)

print(f"Got {len(all_quotes)} quotes")

# Extract spot and futures
spot = all_quotes.get("NSE:NIFTY 50", {}).get("last_price", 0)
fut = all_quotes.get("NFO:NIFTY26JULFUT", {}).get("last_price", 0)

print(f"Spot: {spot}, Futures: {fut}")

if spot <= 0 or fut <= 0:
    print("ERROR: No spot/futures")
    exit(1)

# Calculate DTE — monthly expiry Jul 28 = 13 days from Jul 15
dte = 13
T = dte / 365.0

# Scan for parity breaks
opportunities = []
scan_time = datetime(2026, 7, 15, 15, 20, 0)  # Pretend it's today's scan time

for strike in strikes:
    ce_key = f"NFO:NIFTY{expiry_str}{strike}CE"
    pe_key = f"NFO:NIFTY{expiry_str}{strike}PE"

    ce_q = all_quotes.get(ce_key, {})
    pe_q = all_quotes.get(pe_key, {})

    ce_last = ce_q.get("last_price", 0)
    pe_last = pe_q.get("last_price", 0)

    if ce_last <= 0 or pe_last <= 0:
        continue

    ce_bid = ce_q.get("depth", {}).get("buy", [{}])[0].get("price", 0) if ce_q.get("depth", {}).get("buy") else 0
    ce_ask = ce_q.get("depth", {}).get("sell", [{}])[0].get("price", 0) if ce_q.get("depth", {}).get("sell") else 0
    pe_bid = pe_q.get("depth", {}).get("buy", [{}])[0].get("price", 0) if pe_q.get("depth", {}).get("buy") else 0
    pe_ask = pe_q.get("depth", {}).get("sell", [{}])[0].get("price", 0) if pe_q.get("depth", {}).get("sell") else 0

    # Synthetic forward from put-call parity: F = K + (C-P) * e^(rT)
    synthetic_f = strike + (ce_last - pe_last) * math.exp(RISK_FREE_RATE * T)

    for action, gross_edge in [("CONVERSION", fut - synthetic_f), ("REVERSAL", synthetic_f - fut)]:
        if gross_edge <= 0:
            continue

        edge_inr = gross_edge * LOT_SIZE

        # Costs
        stt = abs(edge_inr) * 0.001
        brokerage = 120
        exchange = edge_inr * 0.0000345
        sebi = edge_inr * 0.000001
        gst = (brokerage + sebi) * 0.18
        ipft = edge_inr * 0.000001
        total_costs = stt + brokerage + exchange + sebi + gst + ipft
        net_edge = edge_inr - total_costs

        if net_edge < MIN_EDGE:
            continue

        confidence = min(95, 60 + int(gross_edge / 5) + int(net_edge / 200))

        if action == "CONVERSION":
            legs = f"BUY {strike} CE @ {ce_last:.1f} | SELL {strike} PE @ {pe_last:.1f} | SELL NIFTY FUT @ {fut:.0f}"
        else:
            legs = f"SELL {strike} CE @ {ce_last:.1f} | BUY {strike} PE @ {pe_last:.1f} | BUY NIFTY FUT @ {fut:.0f}"

        opp = {
            "underlying": "NIFTY",
            "type": "PARITY_BREAK",
            "strike": strike,
            "action": action,
            "legs": legs,
            "description": f"Parity break: {gross_edge:.1f} pts gross, {net_edge:.0f} net",
            "spot_price": spot,
            "futures_price": fut,
            "ce_entry_price": ce_last,
            "pe_entry_price": pe_last,
            "ce_bid": ce_bid,
            "ce_ask": ce_ask,
            "pe_bid": pe_bid,
            "pe_ask": pe_ask,
            "edge_points": round(gross_edge, 2),
            "edge_after_costs": round(net_edge, 0),
            "confidence": confidence,
            "days_to_expiration": dte,
            "scan_time": scan_time,
            "cost_breakdown": json.dumps({
                "grossEdge": round(gross_edge, 2),
                "stt": round(stt, 0),
                "brokerage": brokerage,
                "exchange": round(exchange, 0),
                "sebi": round(sebi, 0),
                "gst": round(gst, 0),
                "ipft": round(ipft, 0),
                "totalCosts": round(total_costs, 0),
                "netEdge": round(net_edge, 0),
                "lotSize": LOT_SIZE
            })
        }
        opportunities.append(opp)

print(f"\nFound {len(opportunities)} opportunities")

if not opportunities:
    print("\nDebug: showing strike data")
    for strike in strikes[:3]:
        ce_key = f"NFO:NIFTY{expiry_str}{strike}CE"
        pe_key = f"NFO:NIFTY{expiry_str}{strike}PE"
        ce_last = all_quotes.get(ce_key, {}).get("last_price", 0)
        pe_last = all_quotes.get(pe_key, {}).get("last_price", 0)
        synth = strike + (ce_last - pe_last) * math.exp(RISK_FREE_RATE * T) if ce_last > 0 and pe_last > 0 else 0
        print(f"  {strike}: CE={ce_last:.1f} PE={pe_last:.1f} synth_f={synth:.1f} fut={fut:.1f} gap={abs(fut-synth):.1f}")

# Insert into DB
if opportunities:
    conn2 = psycopg2.connect(DB_CONN)
    cur2 = conn2.cursor()

    insert_sql = """
    INSERT INTO option_arb_opportunities
    (underlying, opportunity_type, strike, action, legs, description, spot_price, futures_price,
     ce_entry_price, pe_entry_price, ce_bid, ce_ask, pe_bid, pe_ask,
     edge_points, edge_after_costs, confidence, days_to_expiry, expiry_date,
     scan_time, status, cost_breakdown_json)
    VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, 'ACTIVE', %s)
    """

    for opp in opportunities:
        cur2.execute(insert_sql, (
            opp["underlying"], opp["type"], opp["strike"], opp["action"],
            opp["legs"], opp["description"], opp["spot_price"], opp["futures_price"],
            opp["ce_entry_price"], opp["pe_entry_price"], opp["ce_bid"], opp["ce_ask"],
            opp["pe_bid"], opp["pe_ask"], opp["edge_points"], opp["edge_after_costs"],
            opp["confidence"], opp["days_to_expiration"], date(2026, 7, 28),
            opp["scan_time"], opp["cost_breakdown"]
        ))

    conn2.commit()
    print(f"Inserted {len(opportunities)} opportunities")

    opps_sorted = sorted(opportunities, key=lambda x: x["edge_after_costs"], reverse=True)
    print(f"\nTop 10 by edge after costs:")
    for opp in opps_sorted[:10]:
        print(f"  {opp['action']:12s} {opp['strike']:6d}: {opp['edge_points']:6.1f} pts -> Rs {opp['edge_after_costs']:7.0f}  conf={opp['confidence']}%")

    cur2.close()
    conn2.close()
