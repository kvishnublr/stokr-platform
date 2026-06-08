#!/usr/bin/env python3
"""Call internal zerodha status + trigger sync via jdbc-adjacent curl endpoints."""
import json, paramiko
c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)

def run(cmd):
    _, o, e = c.exec_command(cmd, timeout=120)
    return (o.read()+e.read()).decode()

# grep for broker orchestration endpoint
print(run("""curl -s http://127.0.0.1:8080/v3/api-docs 2>/dev/null | python3 -c "
import json,sys
d=json.load(sys.stdin)
paths=[p for p in d.get('paths',{}) if 'broker' in p.lower() or 'zerodha' in p.lower() or 'position' in p.lower()]
for p in sorted(paths)[:40]: print(p)
" 2>/dev/null || echo 'no openapi'"""))

login = json.loads(run("""curl -s -X POST http://127.0.0.1:8080/api/auth/login -H 'Content-Type: application/json' -d '{"principal":"vishnualgo@gmail.com","password":"Temp1234.."}'"""))
print("vishnu login:", str(login)[:300])

login2 = json.loads(run("""curl -s -X POST http://127.0.0.1:8080/api/auth/login -H 'Content-Type: application/json' -d '{"principal":"admin@stokr.local","password":"admin123"}'"""))
admin = login2["data"]["accessToken"]

for path in [
    "/api/trader/broker/zerodha/status",
    "/api/user/broker/zerodha/status",
    "/api/broker/zerodha/status",
    "/api/trader/zerodha/status",
]:
    r = run(f"curl -s -o /dev/null -w '%{{http_code}}' -H 'Authorization: Bearer {admin}' 'http://127.0.0.1:8080{path}'")
    if r.strip() != "404":
        print(path, r, run(f"curl -s -H 'Authorization: Bearer {admin}' 'http://127.0.0.1:8080{path}'")[:500])

print("\n=== fetch_failed logs since deploy ===")
print(run("docker logs stokr-api --since 15m 2>&1 | grep -i 'fetch_failed\\|position.*failed\\|kite.*error\\|positions response' | tail -15"))

c.close()
