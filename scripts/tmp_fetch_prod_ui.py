#!/usr/bin/env python3
import os, paramiko
host = "173.249.55.84"
user = "root"
pw = os.environ.get("STOKR_PROD_SSH_PASS", "Temp1234..")
ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect(host, username=user, password=pw, timeout=20)

cmds = [
    "wc -c /var/www/stokr/new/trader/index.html /var/www/stokr/new/admin/index.html",
    "grep -n 'loadAll\\|refreshAll\\|ensureAuth\\|accessToken\\|/api/' /var/www/stokr/new/trader/index.html | head -40",
    "grep -n 'STOKR_CORS\\|allowedOrigins\\|8082' /opt/stokr/stokr-platform/.env /opt/stokr/stokr-platform/docker-compose*.yml 2>/dev/null | head -20",
    "docker exec stokr-api printenv | grep -i cors || true",
]
for cmd in cmds:
    _, stdout, stderr = ssh.exec_command(cmd)
    print("===", cmd, "===")
    print(stdout.read().decode())
    err = stderr.read().decode()
    if err.strip():
        print("ERR:", err[:500])
ssh.close()
