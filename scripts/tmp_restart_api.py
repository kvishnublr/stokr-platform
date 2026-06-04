#!/usr/bin/env python3
import paramiko
import time

HOST, USER, PWD = "173.249.55.84", "root", "Temp1234.."
BASE = "/opt/stokr/stokr-platform"
cmds = [
    "docker rm -f 75beb0b11be8 2>/dev/null || true",
    f"cd {BASE} && docker compose --profile app up -d --force-recreate --no-deps api 2>&1",
    "sleep 60",
    "curl -sf http://localhost:8080/actuator/health 2>/dev/null | head -c 200 || echo FAIL",
    "docker ps --filter name=stokr-api --format '{{.Names}} {{.Status}}'",
]

client = paramiko.SSHClient()
client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
client.connect(HOST, username=USER, password=PWD, timeout=30)
for cmd in cmds:
    print(">>>", cmd[:80])
    _, stdout, stderr = client.exec_command(cmd, timeout=900)
    out = stdout.read().decode("ascii", errors="replace")
    err = stderr.read().decode("ascii", errors="replace")
    print((out or err).strip()[-2000:])
client.close()
