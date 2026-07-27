#!/usr/bin/env python3
"""Fix and restart backend deployment."""
import paramiko, time, sys

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("173.249.55.84", username="root", password="`$SSH_PASSWORD", timeout=30)

def cmd(c, show=True):
    stdin, stdout, stderr = ssh.exec_command(c)
    out = stdout.read().decode(errors='replace').strip()
    err = stderr.read().decode(errors='replace').strip()
    if show and out: print(out)
    if err: print(f"ERR: {err[-200:]}")
    return out, err

print("=== Check current containers ===")
cmd("docker ps -a | grep stokr")

print("\n=== Read docker-compose.yml ===")
out, _ = cmd("cat /root/stokr-platform/stokr-lite/docker-compose.yml")
print(out)

print("\n=== Try docker compose build ===")
# Kill anything related first
cmd("docker rm -f stokr-lite-backend 2>/dev/null; echo done", show=False)

# Rebuild and start
cmd("cd /root/stokr-platform/stokr-lite && docker compose up -d --build 2>&1", show=True)

time.sleep(15)

print("\n=== Verify ===")
cmd("docker ps | grep stokr")

# Check backend logs
out, _ = cmd("docker logs stokr-lite-backend --tail 30 2>&1", show=False)
for line in out.split('\n')[-15:]:
    print(line)

# If still down, try direct java
out2, _ = cmd("docker ps | grep 'stokr-lite-backend'")
if not out2.strip():
    print("\n!!! Backend container still not running. Trying direct java...")
    cmd("cd /root/stokr-platform/stokr-lite/backend && ls target/*.jar 2>/dev/null || echo 'no jar found'")
    cmd("ls /root/stokr-platform/stokr-lite/backend/app.jar 2>/dev/null || echo 'no app.jar'")
    # Check if maven build succeeded
    out, _ = cmd("cd /root/stokr-platform/stokr-lite/backend && mvn package -DskipTests 2>&1 | tail -20", show=True)

ssh.close()

