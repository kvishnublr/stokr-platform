#!/usr/bin/env python3
import paramiko

c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)

def run(cmd, timeout=60):
    _, o, e = c.exec_command(cmd, timeout=timeout)
    out = (o.read() + e.read()).decode("utf-8", "replace")
    print(f"\n$ {cmd}\n{out.strip()[:4000]}\n")

run('docker ps --filter name=stokr-api --filter name=stokr-postgres --format "{{.Names}} {{.Status}}"')
run("docker logs stokr-api 2>&1 | tail -40")
c.close()
