#!/usr/bin/env python3
import paramiko
import sys
import time

c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)


def run(cmd, timeout=60):
    _, o, e = c.exec_command(cmd, timeout=timeout)
    out = (o.read() + e.read()).decode("utf-8", "replace")
    code = o.channel.recv_exit_status()
    sys.stdout.buffer.write(f"$ {cmd}\n".encode())
    sys.stdout.buffer.write(out.encode("utf-8", errors="replace"))
    sys.stdout.buffer.write(f"\nexit={code}\n\n".encode())
    sys.stdout.flush()
    return code, out


for i in range(12):
    code, out = run("curl -s -o /dev/null -w 'health=%{http_code}' http://127.0.0.1:8080/actuator/health")
    if "health=200" in out:
        break
    time.sleep(30)

run('docker ps --filter name=stokr-api --filter name=stokr-ui --format "{{.Names}} {{.Status}}"')
run("cd /opt/stokr/stokr-platform && git rev-parse --short HEAD")
run("curl -s http://127.0.0.1:8080/actuator/health | head -c 400")
run("docker logs stokr-api 2>&1 | grep -iE 'error|exception|flyway|failed|started StokrApplication' | tail -20")
run("docker logs stokr-api 2>&1 | tail -15")
c.close()
