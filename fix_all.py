import paramiko

s = paramiko.SSHClient()
s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect("173.249.55.84", username="root", password="19119e3a6793dde1", timeout=30)

def c(cmd):
    i, o, e = s.exec_command(cmd)
    return o.read().decode(errors='replace').strip()

# Check what databases exist
print("=== Databases ===")
print(c("su - postgres -c 'psql -l' 2>&1 | grep stokr"))

# Restore original docker-compose with correct env refs
orig_compose = """services:
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
      SPRING_DATASOURCE_URL: ${SPRING_DATASOURCE_URL:-jdbc:postgresql://localhost:5432/stokr_platform}
      SPRING_DATASOURCE_USERNAME: ${SPRING_DATASOURCE_USERNAME:-postgres}
      SPRING_DATASOURCE_PASSWORD: ${SPRING_DATASOURCE_PASSWORD:-root123}
      JWT_SECRET: change-this-to-a-very-long-random-secret-key-at-least-256-bits
      STOKR_UI_BASE_URL: https://stokr.in
    restart: unless-stopped
"""

sftp = s.open_sftp()
with sftp.file("/root/stokr-platform/stokr-lite/docker-compose.yml", "w") as f:
    f.write(orig_compose)
sftp.close()
print("Updated compose with correct DB: stokr_platform / root123")

# Restart
c("docker rm -f stokr-lite-backend stokr-lite-frontend 2>/dev/null; echo done")
print(c("cd /root/stokr-platform/stokr-lite && docker compose up -d --build 2>&1 | tail -5"))

import time
time.sleep(20)

print("\n=== Containers ===")
print(c("docker ps --format '{{.Names}} {{.Status}}' | grep stokr"))

print("\n=== Backend Logs ===")
for line in c("docker logs stokr-lite-backend --tail 20 2>&1").split('\n'):
    if any(k in line for k in ['Started', 'Tomcat', 'Error', 'Flyway', 'migrate', '8080', 'JVM', 'WARN', 'Exception']):
        print(f"  {line[:250]}")

import time
time.sleep(5)
print("\n=== Health ===")
print(c("curl -s http://localhost:8080/actuator/health"))

print("\n=== QuickFlip Model ===")
print(c("curl -s http://localhost:8080/api/backtest/quickflip/model | python3 -m json.tool 2>/dev/null || curl -s http://localhost:8080/api/backtest/quickflip/model"))

s.close()
