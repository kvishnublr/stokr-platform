#!/usr/bin/env python3
import paramiko

c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)

def run(cmd, timeout=30):
    _, o, e = c.exec_command(cmd, timeout=timeout)
    out = (o.read() + e.read()).decode("utf-8", "replace")
    print(f"\n$ {cmd}\n{out.strip()[:3000]}\n")

run("cat /opt/stokr/stokr-platform/Caddyfile 2>/dev/null || docker exec stokr-caddy cat /etc/caddy/Caddyfile 2>/dev/null")
run("docker exec stokr-ui cat /etc/nginx/conf.d/default.conf 2>/dev/null || docker exec stokr-ui cat /etc/nginx/nginx.conf 2>/dev/null | head -80")
run("""curl -s -w '\\nHTTP=%{http_code}' -X POST https://stokr.in/api/auth/login -H 'Content-Type: application/json' -d '{"principal":"admin@stokr.local","password":"wrong"}'""")
run("docker ps --filter name=stokr-ui --format '{{.Names}} {{.Status}}'")
run("docker network inspect stokr-platform_default 2>/dev/null | grep -A2 stokr-ui | head -20")
c.close()
