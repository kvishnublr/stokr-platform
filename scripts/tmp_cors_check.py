#!/usr/bin/env python3
import paramiko

c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)

def run(cmd):
    _, o, e = c.exec_command(cmd, timeout=30)
    out = (o.read() + e.read()).decode("utf-8", "replace")
    print(f"\n$ {cmd}\n{out.strip()[:2000]}\n")

run("docker exec stokr-api printenv | grep -i cors")
run("grep -i cors /opt/stokr/stokr-platform/.env 2>/dev/null || true")
run("""curl -s -w '\\nHTTP=%{http_code}' -X POST http://127.0.0.1:8080/api/auth/login -H 'Content-Type: application/json' -H 'Origin: https://stokr.in' -d '{"principal":"admin","password":"admin123"}'""")
run("""curl -s -w '\\nHTTP=%{http_code}' -X OPTIONS http://127.0.0.1:8080/api/auth/login -H 'Origin: https://stokr.in' -H 'Access-Control-Request-Method: POST' -H 'Access-Control-Request-Headers: content-type' -i | head -20""")
c.close()
