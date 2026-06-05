#!/usr/bin/env python3
import paramiko
c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)

def run(cmd):
    _, o, e = c.exec_command(cmd, timeout=30)
    out = (o.read() + e.read()).decode(errors="replace").strip()
    print(f"{cmd}\n=> {out}\n")

run("cd /opt/stokr/stokr-platform && git rev-parse --short HEAD")
run('docker ps --filter name=stokr --format "{{.Names}} {{.Status}}"')
run("curl -s -o /dev/null -w 'api=%{http_code}' http://127.0.0.1:8080/actuator/health")
run("curl -s -o /dev/null -w 'ui=%{http_code}' http://127.0.0.1:3000/ 2>/dev/null || echo ui_unreachable")
c.close()
