#!/usr/bin/env python3
import paramiko
c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)

def run(cmd, t=120):
    _, o, e = c.exec_command(cmd, timeout=t)
    return (o.read()+e.read()).decode()

print("=== DOCKER PS (ui/nginx) ===")
print(run("docker ps --format '{{.Names}} {{.Ports}} {{.Status}}' | grep -iE 'ui|8082|8083|nginx'"))

print("\n=== LISTEN 8082/8083 ===")
print(run("ss -tlnp | grep -E '8082|8083'"))

print("\n=== INDEX.HTML HEAD (8082) ===")
print(run("curl -s http://127.0.0.1:8082/index.html | head -30"))

print("\n=== SCRIPT SECTION (8082) ===")
print(run("curl -s http://127.0.0.1:8082/index.html | grep -E 'API_BASE|fetch\\(|/api/' | head -30"))

print("\n=== NGINX CONFIG ===")
print(run("grep -r '8082\\|8083' /etc/nginx/ 2>/dev/null | head -20"))
print(run("ls -la /var/www/stokr/ 2>/dev/null | head -20"))
print(run("find /opt/stokr /var/www -name 'index.html' 2>/dev/null | head -15"))

c.close()
