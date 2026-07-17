import psycopg2, hashlib, json, urllib.request, urllib.parse, http.cookiejar

conn = psycopg2.connect(host='localhost', dbname='stokr_lite', user='postgres', password='stokr2026')
cur = conn.cursor()

# Get account 1 credentials
cur.execute("SELECT client_id, zerodha_password, zerodha_totp_secret FROM broker_accounts WHERE id = 1")
row = cur.fetchone()
user_id, password, totp_secret = row
print(f"Account: {user_id}")

# Get API key from env
import os
api_key = None
api_secret = None
with open('/opt/stokr/stokr-lite.env') as f:
    for line in f:
        if line.startswith('ZERODHA_API_KEY='):
            api_key = line.split('=', 1)[1].strip()
        if line.startswith('ZERODHA_API_SECRET='):
            api_secret = line.split('=', 1)[1].strip()

print(f"API Key: {api_key}")

# Step 1: Generate TOTP
import subprocess
result = subprocess.run(['python3', '-c', f'''
import pyotp
totp = pyotp.TOTP("{totp_secret}")
print(totp.now())
'''], capture_output=True, text=True)
totp_code = result.stdout.strip()
print(f"TOTP: {totp_code}")

# Step 2: Login to Kite
cookie_jar = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cookie_jar))

login_data = urllib.parse.urlencode({'user_id': user_id, 'password': password}).encode()
req = urllib.request.Request('https://kite.zerodha.com/api/login', data=login_data,
    headers={'Content-Type': 'application/x-www-form-urlencoded', 'User-Agent': 'Mozilla/5.0'})
resp = opener.open(req)
login_result = json.loads(resp.read())
print(f"Login: {login_result.get('status')}")

if login_result.get('status') != 'success':
    print(f"Login failed: {login_result}")
    exit(1)

request_id = login_result['data']['request_id']

# Step 3: 2FA
twofa_data = urllib.parse.urlencode({'user_id': user_id, 'twofa_value': totp_code, 'request_id': request_id}).encode()
req = urllib.request.Request('https://kite.zerodha.com/api/twofa', data=twofa_data,
    headers={'Content-Type': 'application/x-www-form-urlencoded', 'User-Agent': 'Mozilla/5.0'})
resp = opener.open(req)
twofa_result = json.loads(resp.read())
print(f"2FA: {twofa_result.get('status')}")

if twofa_result.get('status') != 'success':
    print(f"2FA failed: {twofa_result}")
    exit(1)

# Step 4: Get OAuth redirect to extract request_token
auth_url = f'https://kite.zerodha.com/connect/login?v=3&api_key={api_key}'
req = urllib.request.Request(auth_url, headers={'User-Agent': 'Mozilla/5.0'})

# Follow redirects manually to capture the request_token
class NoRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None

opener2 = urllib.request.build_opener(
    urllib.request.HTTPCookieProcessor(cookie_jar),
    NoRedirectHandler
)

try:
    req = urllib.request.Request(auth_url, headers={'User-Agent': 'Mozilla/5.0'})
    resp = opener2.open(req)
    # If we get here, it's a 200 page, not a redirect
    body = resp.read().decode()
    print(f"Auth page: {resp.status}")
except urllib.error.HTTPError as e:
    if e.code in (301, 302, 303, 307):
        redirect_url = e.headers.get('Location', '')
        print(f"Redirect: {redirect_url}")
        # Extract request_token from redirect URL
        if 'request_token=' in redirect_url:
            request_token = redirect_url.split('request_token=')[1].split('&')[0]
            print(f"Request token: {request_token}")
        else:
            # Need to follow one more redirect
            req2 = urllib.request.Request(redirect_url, headers={'User-Agent': 'Mozilla/5.0'})
            try:
                resp2 = opener2.open(req2)
            except urllib.error.HTTPError as e2:
                if e2.code in (301, 302, 303, 307):
                    redirect_url2 = e2.headers.get('Location', '')
                    print(f"Redirect2: {redirect_url2}")
                    if 'request_token=' in redirect_url2:
                        request_token = redirect_url2.split('request_token=')[1].split('&')[0]
                        print(f"Request token: {request_token}")
    else:
        print(f"Error: {e.code} {e.read().decode()[:200]}")
        exit(1)

# Step 5: Exchange request_token for access_token
checksum = hashlib.sha256((api_key + request_token + api_secret).encode()).hexdigest()
exchange_data = urllib.parse.urlencode({
    'api_key': api_key,
    'request_token': request_token,
    'checksum': checksum
}).encode()
req = urllib.request.Request('https://api.kite.trade/session/token', data=exchange_data,
    headers={'Content-Type': 'application/x-www-form-urlencoded'})
resp = urllib.request.urlopen(req)
exchange_result = json.loads(resp.read())
print(f"Exchange: {exchange_result.get('status')}")

if exchange_result.get('status') == 'success':
    access_token = exchange_result['data']['access_token']
    print(f"\nACCESS TOKEN: {access_token}")
    
    # Step 6: Save to DB
    cur.execute("""
        UPDATE broker_accounts 
        SET access_token = %s, 
            refresh_token = %s,
            token_expiry = NOW() + INTERVAL '24 hours',
            status = 'ACTIVE',
            last_auto_reconnect = NOW()
        WHERE id = 1
    """, (access_token, exchange_result['data'].get('refresh_token', '')))
    conn.commit()
    print("Token saved to DB!")
    
    # Step 7: Verify by calling API
    req = urllib.request.Request('https://api.kite.trade/portfolio/positions',
        headers={'Authorization': f'token {api_key}:{access_token}', 'User-Agent': 'Mozilla/5.0'})
    resp = urllib.request.urlopen(req)
    print(f"API verification: {resp.status} OK")
else:
    print(f"Exchange failed: {exchange_result}")

conn.close()
