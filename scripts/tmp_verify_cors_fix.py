#!/usr/bin/env python3
import time
import paramiko

time.sleep(60)
c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)

def run(cmd, timeout=60):
    _, o, e = c.exec_command(cmd, timeout=timeout)
    out = (o.read() + e.read()).decode("utf-8", "replace")
    print(f"\n$ {cmd}\n{out.strip()[:1500]}\n")

run("curl -s -o /dev/null -w 'health=%{http_code}' http://127.0.0.1:8080/actuator/health")
run("""curl -s -w '\\nHTTP=%{http_code}' -X POST http://127.0.0.1:8080/api/auth/login -H 'Content-Type: application/json' -H 'Origin: https://stokr.in' -d '{"principal":"admin@stokr.local","password":"admin123"}'""")
run("""curl -s -w '\\nHTTP=%{http_code}' -X POST https://stokr.in/api/auth/login -H 'Content-Type: application/json' -H 'Origin: https://stokr.in' -d '{"principal":"admin@stokr.local","password":"admin123"}'""")
c.close()
