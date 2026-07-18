import subprocess, json, urllib.request, urllib.error, math, datetime, calendar
from urllib.parse import quote
from datetime import date

def get_token():
    p = subprocess.run(
        ['psql', '-h', 'localhost', '-U', 'postgres', '-d', 'stokr_lite', '-t', '-A', '-c',
         "SELECT access_token FROM broker_accounts WHERE broker_name='ZERODHA' ORDER BY id DESC LIMIT 1"],
        capture_output=True, text=True, env={'PGPASSWORD': 'stokr2026'}
    )
    return p.stdout.strip()

TOKEN = get_token()
API_KEY = 'zazlrld244cc6jf0'
HEADERS = {'Authorization': f'token {API_KEY}:{TOKEN}', 'X-Kite-Version': '3'}

def api_get(path):
    url = 'https://api.kite.trade' + path
    req = urllib.request.Request(url, headers=HEADERS)
    resp = urllib.request.urlopen(req)
    return json.loads(resp.read())

def api_quote(instruments):
    parts = [f'i={quote(inst)}' for inst in instruments]
    return api_get(f'/quote?{"&".join(parts)}')

def get_depth_price(depth, side):
    levels = depth.get(side, [])
    if levels and isinstance(levels, list) and len(levels) > 0:
        price = levels[0].get('price', 0)
        qty = levels[0].get('quantity', 0)
        oi = levels[0].get('open_interest', 0)
        return float(price), int(qty), int(oi)
    return 0.0, 0, 0

RISK_FREE = 0.065

UNDERLYINGS = {
    'NIFTY':      {'spot': 'NSE:NIFTY 50',       'futures': 'NFO:NIFTY26JULFUT', 'lot': 65},
    'BANKNIFTY':  {'spot': 'NSE:NIFTY BANK',     'futures': 'NFO:BANKNIFTY26JULFUT', 'lot': 30},
    'MIDCPNIFTY': {'spot': 'NSE:NIFTY MID SELECT','futures': 'NFO:MIDCPNIFTY26JULFUT', 'lot': 120},
    'FINNIFTY':   {'spot': 'NSE:NIFTY FIN SERVICE','futures': 'NFO:FINNIFTY26JULFUT', 'lot': 60},
}

def get_monthly_expiry(year, month):
    last_day = calendar.monthrange(year, month)[1]
    for d in range(last_day, 0, -1):
        if date(year, month, d).weekday() == 1:
            return date(year, month, d)
    return None

def get_weekly_expiry_nifty(ref_date):
    for i in range(0, 14):
        d = ref_date + datetime.timedelta(days=i)
        if d.weekday() == 1:
            return d
    return ref_date

def make_option_symbol(underlying, strike, ce_pe, expiry_date):
    if underlying == 'NIFTY':
        return f"NIFTY{expiry_date.strftime('%y')}{expiry_date.month}{expiry_date.day}{strike}{ce_pe}"
    else:
        return f"{underlying}{expiry_date.strftime('%y')}{expiry_date.strftime('%b').upper()}{strike}{ce_pe}"

print(f"{'='*90}")
print(f"OPTION ARB SCANNER — {datetime.datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
print(f"{'='*90}\n")

today = date.today()
total_opps = 0

for underlying, cfg in UNDERLYINGS.items():
    lot = cfg['lot']
    
    if underlying == 'NIFTY':
        monthly_exp = get_weekly_expiry_nifty(today)
        label = "weekly"
    else:
        monthly_exp = get_monthly_expiry(today.year, today.month)
        label = "monthly"
    
    if not monthly_exp:
        print(f"{underlying}: No expiry found")
        continue
    
    dte = (monthly_exp - today).days
    if dte < 0:
        if underlying == 'NIFTY':
            monthly_exp = get_weekly_expiry_nifty(today)
            dte = (monthly_exp - today).days
        else:
            monthly_exp = get_monthly_expiry(today.year, today.month + 1) if today.month < 12 else get_monthly_expiry(today.year + 1, 1)
            if monthly_exp:
                dte = (monthly_exp - today).days
    
    print(f"{'='*90}")
    print(f"{underlying} | Spot: ? | Expiry: {monthly_exp} ({label}, DTE={dte}) | Lot: {lot}")
    
    try:
        spot_resp = api_quote([cfg['spot']])
        spot_data = spot_resp.get('data', {})
        spot_quote = spot_data.get(cfg['spot'], {})
        spot_price = float(spot_quote.get('last_price', 0))
        
        fut_data = {}
        fut_price = 0
        try:
            fut_resp = api_quote([cfg['futures']])
            fut_data = fut_resp.get('data', {})
            fut_quote = fut_data.get(cfg['futures'], {})
            fut_price = float(fut_quote.get('last_price', 0))
        except:
            pass
        
        print(f"  Spot: {spot_price} | Futures: {fut_price}")
        
        if spot_price <= 0:
            print(f"  No data available\n")
            continue
        
        near_strike = round(spot_price / 50) * 50
        strikes = [near_strike - 200, near_strike - 150, near_strike - 100, near_strike - 50,
                   near_strike, near_strike + 50, near_strike + 100, near_strike + 150, near_strike + 200]
    except Exception as e:
        print(f"  Error: {e}\n")
        continue
    
    ce_syms = []
    pe_syms = []
    for strike in strikes:
        ce_name = make_option_symbol(underlying, strike, 'CE', monthly_exp)
        pe_name = make_option_symbol(underlying, strike, 'PE', monthly_exp)
        ce_syms.append((strike, f'NFO:{ce_name}'))
        pe_syms.append((strike, f'NFO:{pe_name}'))
    
    all_inst = [cfg['spot'], cfg['futures']] + [s for _, s in ce_syms] + [s for _, s in pe_syms]
    
    try:
        resp = api_quote(all_inst)
        quotes = resp.get('data', {})
    except Exception as e:
        print(f"  Quote error: {e}\n")
        continue
    
    if not quotes:
        print(f"  No quotes returned\n")
        continue
    
    print(f"\n  {'Strike':>8s} | {'CE Bid':>8s} {'CE Ask':>8s} {'Spd':>5s} {'CE Vol':>7s} | {'PE Bid':>8s} {'PE Ask':>8s} {'Spd':>5s} {'PE Vol':>7s} | {'Deviation':>9s} {'Edge':>8s}")
    print(f"  {'-'*8}-+-{'-'*8}-{'-'*8}-{'-'*5}-{'-'*7}-+-{'-'*8}-{'-'*8}-{'-'*5}-{'-'*7}-+-{'-'*9}-{'-'*8}")
    
    T = dte / 365.0
    disc = math.exp(-RISK_FREE * T) if T > 0 else 1.0
    
    for i, strike in enumerate(strikes):
        ce_key = ce_syms[i][1]
        pe_key = pe_syms[i][1]
        
        ce_q = quotes.get(ce_key, {})
        pe_q = quotes.get(pe_key, {})
        
        if not ce_q or not pe_q:
            continue
        
        ce_depth = ce_q.get('depth', {})
        pe_depth = pe_q.get('depth', {})
        ce_last = float(ce_q.get('last_price', 0))
        pe_last = float(pe_q.get('last_price', 0))
        ce_vol = int(ce_q.get('volume', 0))
        pe_vol = int(pe_q.get('volume', 0))
        ce_oi = int(ce_q.get('open_interest', 0))
        pe_oi = int(pe_q.get('open_interest', 0))
        
        ce_bid, ce_bid_q, ce_bid_oi = get_depth_price(ce_depth, 'buy')
        ce_ask, ce_ask_q, ce_ask_oi = get_depth_price(ce_depth, 'sell')
        pe_bid, pe_bid_q, pe_bid_oi = get_depth_price(pe_depth, 'buy')
        pe_ask, pe_ask_q, pe_ask_oi = get_depth_price(pe_depth, 'sell')
        
        ce_spread = ce_ask - ce_bid if ce_ask > 0 and ce_bid > 0 else 0
        pe_spread = pe_ask - pe_bid if pe_ask > 0 and pe_bid > 0 else 0
        
        if ce_bid > 0 and ce_ask > 0 and pe_bid > 0 and pe_ask > 0 and fut_price > 0 and T > 0:
            synthetic_f = (ce_ask - pe_bid) / disc + strike
            deviation = synthetic_f - fut_price
            dev_pct = (deviation / fut_price) * 100 if fut_price > 0 else 0
            
            # Conversion: buy CE ask + sell PE bid = -(ce_ask) + pe_bid (credit)
            # Actually: CE-PE = F-K => C = P + (F-K)*e^(-rT)
            # Parity: C - P should equal (F - K) * e^(-rT)
            theoretical = (fut_price - strike) * disc
            actual = ce_bid - pe_ask  # what you'd get if you sell CE and buy PE
            actual_rev = ce_ask - pe_bid  # cost to buy CE and sell PE
            
            total_spread = ce_spread + pe_spread
            
            edge = 0
            if abs(deviation) > 1:
                edge = abs(deviation) - (ce_spread + pe_spread) * 0.5
            
            marker = ""
            if total_spread < 10 and abs(deviation) > 20:
                marker = " *** SPREAD OK ***"
            elif total_spread < 15 and abs(deviation) > 30:
                marker = " ** SPREAD CLOSE **"
            
            if total_spread <= 15 or abs(deviation) > 50:
                print(f"  {strike:>8d} | {ce_bid:>8.2f} {ce_ask:>8.2f} {ce_spread:>5.1f} {ce_vol:>7d} | {pe_bid:>8.2f} {pe_ask:>8.2f} {pe_spread:>5.1f} {pe_vol:>7d} | {deviation:>+9.2f} {edge:>+8.2f}{marker}")
                total_opps += 1
        
        elif ce_ask == 0 or pe_bid == 0 or pe_ask == 0 or ce_bid == 0:
            pass
    
    print()

print(f"{'='*90}")
print(f"Total strikes with spread <= 15pts: {total_opps}")
print(f"Note: Market is likely CLOSED — depth may be stale.")
