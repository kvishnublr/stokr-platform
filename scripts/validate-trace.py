#!/usr/bin/env python3
"""Auto-validation: logs in as admin, tests critical endpoints, prints green/red status.
   Runs standalone. Token auto-renewed on every run — no manual intervention needed."""

import urllib.request, json, sys, os, time, socket

BASE = os.environ.get('API_BASE', 'http://localhost:8080')
ADMIN_USER = os.environ.get('ADMIN_USER', 'admin')
ADMIN_PASS = os.environ.get('ADMIN_PASS', 'admin123')

PASS, FAIL = 0, 0
results = []

def green(s): return f"\033[92m{s}\033[0m"
def red(s):   return f"\033[91m{s}\033[0m"
def yellow(s): return f"\033[93m{s}\033[0m"
def bold(s):  return f"\033[1m{s}\033[0m"

def check(desc, ok, detail=""):
    global PASS, FAIL
    if ok:
        PASS += 1
        results.append(f"  {green('PASS')} {desc}" + (f"  {detail}" if detail else ""))
    else:
        FAIL += 1
        results.append(f"  {red('FAIL')} {desc}" + (f"  {detail}" if detail else ""))

def api(method, path, data=None, token=None, timeout=15):
    req = urllib.request.Request(f'{BASE}{path}', method=method)
    req.add_header('Content-Type', 'application/json')
    if token: req.add_header('Authorization', f'Bearer {token}')
    if data: req.data = json.dumps(data).encode()
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read())

def api_safe(method, path, **kw):
    try: return api(method, path, **kw), None
    except urllib.error.HTTPError as e:
        try: body = e.read().decode()[:300]
        except: body = str(e)
        return None, f"HTTP {e.code}: {body}"
    except urllib.error.URLError as e:
        return None, f"URL error: {e.reason}"
    except socket.timeout:
        return None, "Timeout"
    except Exception as e:
        return None, str(e)

print(bold("\n=== Stokr Platform Auto-Validation ==="))
print(f"Started: {time.strftime('%Y-%m-%d %H:%M:%S IST')}")
print(f"API: {BASE}\n")

# ── Step 0: Wait for API ──
print("0. Waiting for API health...")
for i in range(90):
    resp, err = api_safe('GET', '/actuator/health')
    if resp and resp.get('status') == 'UP':
        check("API health check", True, f"UP (took {i*2}s)")
        break
    time.sleep(2)
else:
    check("API health check", False, "TIMEOUT - API not healthy after 180s")
    for r in results: print(r)
    sys.exit(1)

# ── Step 1: Login (auto-renew token) ──
print("\n1. Authenticating...")
resp, err = api_safe('POST', '/api/auth/login', data={'principal': ADMIN_USER, 'password': ADMIN_PASS})
token = resp.get('data', {}).get('accessToken', '') if resp else ''
check("Admin login", bool(token), err or f"token={token[:20]}...")
if not token:
    for r in results: print(r)
    sys.exit(1)

# ── Step 2: Core admin endpoints ──
print("\n2. Validating critical endpoints...")

# 2a. Admin health
resp, err = api_safe('GET', '/api/admin/health', token=token)
check("GET /api/admin/health", resp is not None, err or f"status={resp.get('data',{}).get('status','?')}")

# 2b. Signal stats
resp, err = api_safe('GET', '/api/admin/signals/stats', token=token)
check("GET /api/admin/signals/stats", resp is not None, err or "OK")

# 2c. Signals list
resp, err = api_safe('GET', '/api/admin/signals?page=0&size=5', token=token)
signals_ok = resp and resp.get('data', {}).get('content') is not None
check("GET /api/admin/signals", signals_ok, err or f"count={len(resp.get('data',{}).get('content',[]))}")

# 2d. OMS stats
resp, err = api_safe('GET', '/api/admin/oms/stats', token=token)
check("GET /api/admin/oms/stats", resp is not None, err or "OK")

# 2e. Operations snapshot
resp, err = api_safe('GET', '/api/admin/operations/snapshot', token=token)
check("GET /api/admin/operations/snapshot", resp is not None, err or "OK")

# 2f. Readiness
resp, err = api_safe('GET', '/api/admin/readiness', token=token)
check("GET /api/admin/readiness", resp is not None, err or f"ready={resp.get('data',{}).get('ready','?')}")

# 2g. Risk dashboard
resp, err = api_safe('GET', '/api/admin/risk-dashboard', token=token)
check("GET /api/admin/risk-dashboard", resp is not None, err or "OK")

# 2h. Admin ops status
resp, err = api_safe('GET', '/api/admin/ops/status', token=token)
check("GET /api/admin/ops/status", resp is not None, err or "OK")

# 2i. Audit log
resp, err = api_safe('GET', '/api/admin/audit', token=token)
check("GET /api/admin/audit", resp is not None, err or "OK")

# 2j. Users list
resp, err = api_safe('GET', '/api/admin/users', token=token)
check("GET /api/admin/users", resp is not None, err or "OK")

# ── Step 3: Pipeline trace (if signals exist) ──
print("\n3. Validating pipeline trace...")
resp, err = api_safe('GET', '/api/admin/signals?page=0&size=1', token=token)
if resp:
    content = resp.get('data', {}).get('content', [])
    if content:
        signal_id = content[0]['id']
        symbol = content[0].get('symbol', '?')
        trace, terr = api_safe('GET', f'/api/admin/signals/{signal_id}/pipeline-trace', token=token)
        if trace:
            d = trace.get('data', {})
            app_stages = len(d.get('applicationPipeline', []))
            users = len(d.get('users', []))
            overall = d.get('overallStatus', '?')
            statuses = [s['status'] for s in d.get('applicationPipeline', [])]
            check(f"Pipeline trace for {symbol}", True, f"overall={overall} stages={app_stages} users={users}")
            for s in d.get('applicationPipeline', []):
                stage_status = s['status']
                if stage_status in ('PASSED',):
                    continue  # quiet for passed
                elif stage_status in ('FAILED', 'BLOCKED'):
                    results.append(f"    {red('BLOCKED')} {s['stage']}: {s.get('rejectionCode','?')} {s.get('rejectionMessage','')}")
                elif stage_status == 'PENDING':
                    results.append(f"    {yellow('PENDING')} {s['stage']}")
            for u in d.get('users', []):
                fs = u.get('finalStatus', '?')
                if fs in ('FILLED', 'PARTIAL_FILL'):
                    results.append(f"    {green('USER OK')} {u.get('displayName','?')} -> {fs}")
                elif fs in ('REJECTED', 'EXECUTION_REJECTED'):
                    results.append(f"    {red('USER FAIL')} {u.get('displayName','?')} -> {fs}  {u.get('lastRejectionCode','')}")
                else:
                    results.append(f"    {yellow('USER PENDING')} {u.get('displayName','?')} -> {fs}")
        else:
            check(f"Pipeline trace for {symbol}", False, terr)
    else:
        check("No signals found", True, "Endpoint works (no data yet)")
else:
    check("Fetch signals for trace", False, err)

# ── Results ──
print(f"\n{'-'*50}")
print(f"  {green('PASS')}: {PASS}  {red('FAIL')}: {FAIL}  TOTAL: {PASS+FAIL}")
print(f"{'-'*50}")
for r in results: print(r)

if FAIL > 0:
    print(f"\n{red(bold('SOME CHECKS FAILED'))}")
    sys.exit(1)
else:
    print(f"\n{green(bold('ALL CHECKS PASSED'))}")
    sys.exit(0)
