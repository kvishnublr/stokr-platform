#!/usr/bin/env python3
import os, paramiko, time
PW = os.environ.get("STOKR_PROD_SSH_PASS", "Temp1234..")
ssh = paramiko.SSHClient(); ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy()); ssh.connect("173.249.55.84", username="root", password=PW, timeout=30)

def run(cmd):
    _, o, e = ssh.exec_command(cmd, timeout=120)
    return (o.read()+e.read()).decode()

print(run(r"""python3 - <<'PY'
from pathlib import Path
vals = {}
for line in Path('/opt/stokr/stokr-platform/.env').read_text().splitlines():
    if '=' in line and not line.strip().startswith('#'):
        k,v = line.split('=',1)
        vals[k.strip()] = v.strip()
pw = vals.get('SPRING_DATASOURCE_PASSWORD') or vals.get('DB_PASSWORD') or vals.get('POSTGRES_PASSWORD')
user = vals.get('SPRING_DATASOURCE_USERNAME') or vals.get('DB_USER') or 'postgres'
import subprocess
sql = "ALTER USER %s WITH PASSWORD '%s';" % (user, pw.replace("'", "''"))
subprocess.check_call(['docker','exec','stokr-postgres','psql','-U','postgres','-d','postgres','-c',sql])
print('altered', user)
PY"""))
print(run("cd /opt/stokr/stokr-platform && docker compose restart api"))
time.sleep(30)
for i in range(15):
    h = run("curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health")
    print("health", i+1, h.strip())
    if h.strip() == "200":
        print(run("curl -s http://127.0.0.1:8080/actuator/health"))
        break
    time.sleep(10)
ssh.close()
