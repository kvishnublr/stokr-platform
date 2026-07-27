#!/usr/bin/env python3
"""Force restart both containers."""
import paramiko, time

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("173.249.55.84", username="root", password="`$SSH_PASSWORD", timeout=30)

def cmd(c):
    stdin, stdout, stderr = ssh.exec_command(c)
    out = stdout.read().decode(errors='replace').strip()
    err = stderr.read().decode(errors='replace').strip()
    return out, err

print("=== Force remove all stokr containers ===")
cmd("docker rm -f stokr-lite-backend stokr-lite-frontend 2>/dev/null; echo 'done'")

print("\n=== Rebuild and start ===")
cmd("cd /root/stokr-platform/stokr-lite && docker compose up -d --build 2>&1")
out, err = cmd("cd /root/stokr-platform/stokr-lite && docker compose up -d 2>&1")
if "Started" in out or "Running" in out:
    print("SUCCESS!")
elif out:
    print(out[-500:])
if err:
    print(f"WARN: {err[-300:]}")

time.sleep(15)

print("\n=== Container Status ===")
out, _ = cmd("docker ps --format '{{.Names}}\t{{.Status}}\t{{.Ports}}' | grep stokr")
print(out)

# Check backend logs
print("\n=== Backend Startup Log ===")
out, _ = cmd("docker logs stokr-lite-backend --tail 20 2>&1", show=False)
for line in out.split('\n'):
    if any(k in line for k in ['Started', 'Tomcat', 'Error', 'Flyway', 'port', 'listening', 'WARN', 'ERROR', 'Exception', 'Caused']):
        print(line)

# Health
print("\n=== Health Check ===")
out, _ = cmd("curl -s http://localhost:8081/actuator/health 2>/dev/null || echo 'backend not responding'")
print(f"Backend (8081): {out[:200]}")

ssh.close()

