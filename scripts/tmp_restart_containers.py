#!/usr/bin/env python3
import paramiko

c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)
cmd = (
    "docker rm -f stokr-api stokr-ui 2>/dev/null; "
    "cd /opt/stokr/stokr-platform && docker compose --profile app up -d api ui; "
    "docker ps --format '{{.Names}} {{.Status}}' | grep stokr"
)
_, o, e = c.exec_command(cmd, timeout=180)
print((o.read() + e.read()).decode())
c.close()
