#!/usr/bin/env python3
"""Fix Docker network and restart backend."""
import paramiko, time

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh_password = os.environ.get("SSH_PASSWORD", "")
if not ssh_password:
    print("ERROR: Set SSH_PASSWORD env var")
    sys.exit(1)
ssh.connect("173.249.55.84", username="root", password=ssh_password, timeout=30)

def cmd(c):
    stdin, stdout, stderr = ssh.exec_command(c)
    out = stdout.read().decode(errors='replace').strip()
    err = stderr.read().decode(errors='replace').strip()
    if err: print(f"ERR: {err[-200:]}")
    return out

# Rewrite docker-compose with host network mode for backend
compose = """services:
  frontend:
    build:
      context: ./frontend
      dockerfile: Dockerfile
    container_name: stokr-lite-frontend
    ports:
      - '8082:80'
    restart: unless-stopped

  backend:
    build:
      context: ./backend
      dockerfile: Dockerfile
    container_name: stokr-lite-backend
    network_mode: host
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://localhost:5432/stokr_lite
      SPRING_DATASOURCE_USERNAME: postgres
      SPRING_DATASOURCE_PASSWORD: ${SPRING_DATASOURCE_PASSWORD:-}
      JWT_SECRET: change-this-to-a-very-long-random-secret-key-at-least-256-bits
      STOKR_UI_BASE_URL: https://stokr.in
    restart: unless-stopped
"""

sftp = ssh.open_sftp()
with sftp.file("/root/stokr-platform/stokr-lite/docker-compose.yml", "w") as f:
    f.write(compose)
sftp.close()
print("Updated docker-compose.yml")

# Kill and restart
cmd("docker rm -f stokr-lite-backend stokr-lite-frontend 2>/dev/null; echo done")
cmd("cd /root/stokr-platform/stokr-lite && docker compose up -d --build 2>&1")

time.sleep(20)

# Status
out = cmd("docker ps --format '{{.Names}} {{.Status}}' | grep stokr")
print(f"\nContainers: {out}")

# Logs
out = cmd("docker logs stokr-lite-backend --tail 15 2>&1")
print("\nBackend logs:")
for line in out.split('\n'):
    if any(k in line for k in ['Started', 'Tomcat', 'Error', 'Flyway', '8080', 'JVM', 'WARN']):
        print(f"  {line[:200]}")

# Health
import time; time.sleep(5)
out = cmd("curl -s http://localhost:8080/actuator/health 2>/dev/null || echo 'not ready'")
print(f"\nHealth: {out[:200]}")

# API test
out = cmd("curl -s http://localhost:8080/api/backtest/portfolio/model 2>/dev/null | head -c 300 || echo 'API fail'")
print(f"\nPortfolio Model API: {out[:300]}")

ssh.close()
print("\nDone!")
