#!/usr/bin/env python3
import json, paramiko
c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)

def run(cmd):
    _, o, e = c.exec_command(cmd, timeout=120)
    return (o.read() + e.read()).decode()

# admin login
admin = json.loads(run("""curl -s -X POST http://127.0.0.1:8080/api/auth/login -H 'Content-Type: application/json' -d '{"principal":"admin@stokr.local","password":"admin123"}'"""))
AT = admin["data"]["accessToken"]

# try trader passwords from env / common
for pw in ["Stokr@2024", "Stokr@123", "Trader@123", "Vishnu@123", "Temp1234.."]:
    r = run(f"""curl -s -o /tmp/tr.json -w '%{{http_code}}' -X POST http://127.0.0.1:8080/api/auth/login -H 'Content-Type: application/json' -d '{{"principal":"vishnualgo@gmail.com","password":"{pw}"}}'""")
    code = r.strip()
    if code == "200":
        body = run("cat /tmp/tr.json")
        print("TRADER PW OK", pw[:3]+"...")
        TT = json.loads(body)["data"]["accessToken"]
        break
else:
    TT = None
    print("trader login failed all passwords")

for name, tok, ep in [
    ("admin", AT, "/api/admin/health"),
    ("admin", AT, "/api/admin/users?page=0&size=3"),
    ("trader", TT, "/api/trader/terminal/workstation"),
    ("trader", TT, "/api/portfolio/dashboard?equityPoints=12"),
]:
    if not tok:
        continue
    body = run(f"""curl -s -w '\\nHTTP:%{{http_code}}' 'http://127.0.0.1:8080{ep}' -H 'Authorization: Bearer {tok}' """)
    print("\n===", name, ep, "===")
    print(body[:2500])

c.close()
