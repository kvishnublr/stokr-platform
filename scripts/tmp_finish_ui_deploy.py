#!/usr/bin/env python3
import os, paramiko, time
HOST, USER, PW = "173.249.55.84", "root", os.environ.get("STOKR_PROD_SSH_PASS", "Temp1234..")
REMOTE = "/var/www/stokr/new"
ssh = paramiko.SSHClient(); ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy()); ssh.connect(HOST, username=USER, password=PW, timeout=30)

def run(cmd):
    _, o, e = ssh.exec_command(cmd, timeout=30)
    o.channel.settimeout(10)
    try:
        out = o.read().decode("utf-8", "replace")
    except Exception:
        out = ""
    try:
        err = e.read().decode("utf-8", "replace")
    except Exception:
        err = ""
    return out + err

print(run(f"cp {REMOTE}/shared/stokr-panel.js {REMOTE}/trader/stokr-panel.js && cp {REMOTE}/shared/stokr-panel.js {REMOTE}/admin/stokr-panel.js"))
print(run("pkill -f 'python3 -m http.server 8082' || true; pkill -f 'python3 -m http.server 8083' || true"))
print(run(f"bash -lc 'cd {REMOTE}/trader && setsid python3 -m http.server 8082 >> /var/log/stokr/new/trader.log 2>&1 < /dev/null &'"))
print(run(f"bash -lc 'cd {REMOTE}/admin && setsid python3 -m http.server 8083 >> /var/log/stokr/new/admin.log 2>&1 < /dev/null &'"))
time.sleep(3)
for c in [
    "ss -ltnp | grep -E ':8082|:8083' || true",
    "curl -s -o /dev/null -w 'trader:%{http_code}\\n' http://127.0.0.1:8082/index.html",
    "curl -s -o /dev/null -w 'js:%{http_code}\\n' http://127.0.0.1:8082/stokr-panel.js",
    "curl -s -o /dev/null -w 'admin:%{http_code}\\n' http://127.0.0.1:8083/index.html",
    "docker exec stokr-api printenv STOKR_CORS_ALLOWED_ORIGINS",
    "grep STOKR_CORS /opt/stokr/stokr-platform/.env",
]:
    print('===', c, '==='); print(run(c))

# restart api to pick up cors from .env if needed
print(run("cd /opt/stokr/stokr-platform && docker compose restart api 2>&1 || docker restart stokr-api"))
time.sleep(10)
print(run("curl -s http://127.0.0.1:8080/actuator/health"))
ssh.close()
