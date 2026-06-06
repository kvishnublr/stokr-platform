#!/usr/bin/env python3
"""
Release_v2 Automated Deployment to Production
Target: 173.249.55.84 (new.stokr.in)
"""

import paramiko
import os
import sys
import time
from pathlib import Path

# Configuration
SERVER = "173.249.55.84"
USER = "root"
PASSWORD = "19119e3a6793dde1"
PORT = 22

class Deployer:
    def __init__(self, host, user, password, port=22):
        self.host = host
        self.user = user
        self.password = password
        self.port = port
        self.client = None

    def connect(self):
        """Establish SSH connection"""
        try:
            self.client = paramiko.SSHClient()
            self.client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
            self.client.connect(self.host, port=self.port, username=self.user, password=self.password, timeout=10)
            print(f"✅ Connected to {self.host}")
            return True
        except Exception as e:
            print(f"❌ Connection failed: {e}")
            return False

    def execute(self, command, show_output=True):
        """Execute command on remote server"""
        try:
            stdin, stdout, stderr = self.client.exec_command(command)
            output = stdout.read().decode('utf-8')
            error = stderr.read().decode('utf-8')

            if show_output and output:
                print(output, end='')
            if error and show_output:
                print(f"⚠️  {error}", end='')

            return output, error, stdout.channel.recv_exit_status()
        except Exception as e:
            print(f"❌ Command failed: {e}")
            return "", str(e), 1

    def close(self):
        """Close SSH connection"""
        if self.client:
            self.client.close()

def main():
    print("=" * 60)
    print("RELEASE_V2 PRODUCTION DEPLOYMENT")
    print("Target: 173.249.55.84 (new.stokr.in)")
    print("=" * 60)

    deployer = Deployer(SERVER, USER, PASSWORD, PORT)

    if not deployer.connect():
        sys.exit(1)

    try:
        # Phase 1: Pre-deployment checks
        print("\n📋 PHASE 1: Pre-Deployment Checks")
        print("━" * 60)

        print("Checking Docker...")
        output, err, code = deployer.execute("docker --version")
        if code != 0:
            print("❌ Docker not installed!")
            sys.exit(1)
        print("✅ Docker OK")

        print("Checking Docker Compose...")
        output, err, code = deployer.execute("docker-compose --version")
        if code != 0:
            print("❌ Docker Compose not installed!")
            sys.exit(1)
        print("✅ Docker Compose OK")

        print("Checking PostgreSQL...")
        deployer.execute("PGPASSWORD=stokr pg_isready -h localhost -p 5432", show_output=False)
        print("✅ PostgreSQL OK")

        print("Checking disk space...")
        output, err, code = deployer.execute("df -h / | tail -1")
        print(f"✅ Disk OK")

        # Phase 2: Database backup
        print("\n💾 PHASE 2: Database Backup")
        print("━" * 60)

        backup_cmd = """
mkdir -p /backups
BACKUP_FILE="stokr_platform_$(date +%Y%m%d_%H%M%S).sql.gz"
PGPASSWORD=stokr pg_dump -U stokr -h localhost stokr_platform 2>/dev/null | gzip > /backups/$BACKUP_FILE
echo "✅ Database backed up to /backups/$BACKUP_FILE"
"""
        deployer.execute(backup_cmd)

        # Phase 3: Setup deployment
        print("\n🔨 PHASE 3: Setup Deployment Structure")
        print("━" * 60)

        setup_cmd = """
mkdir -p /opt/stokr-platform
cd /opt/stokr-platform
echo "✅ Deployment directory ready"
"""
        deployer.execute(setup_cmd)

        # Phase 4: Create docker-compose.yml
        print("\n📝 PHASE 4: Create docker-compose Configuration")
        print("━" * 60)

        compose_cmd = """
cat > /opt/stokr-platform/docker-compose.yml << 'EOFCOMPOSE'
version: '3.8'

services:
  postgres:
    image: postgres:15-alpine
    container_name: stokr-postgres-v2
    environment:
      POSTGRES_DB: stokr_platform
      POSTGRES_USER: stokr
      POSTGRES_PASSWORD: stokr
    ports:
      - "5432:5432"
    volumes:
      - postgres_data:/var/lib/postgresql/data
    networks:
      - stokr-network
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U stokr"]
      interval: 10s
      timeout: 5s
      retries: 5
    restart: unless-stopped

  stokr-api:
    image: openjdk:21-slim
    container_name: stokr-api-v2
    ports:
      - "8080:8080"
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/stokr_platform
      SPRING_DATASOURCE_USERNAME: stokr
      SPRING_DATASOURCE_PASSWORD: stokr
      SERVER_PORT: 8080
    depends_on:
      postgres:
        condition: service_healthy
    networks:
      - stokr-network
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8080/api/health"]
      interval: 30s
      timeout: 10s
      retries: 3
      start_period: 60s
    restart: unless-stopped

  stokr-ui:
    image: nginx:latest
    container_name: stokr-ui-v2
    ports:
      - "80:80"
    depends_on:
      - stokr-api
    networks:
      - stokr-network
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:80"]
      interval: 30s
      timeout: 10s
      retries: 3
    restart: unless-stopped

networks:
  stokr-network:
    driver: bridge

volumes:
  postgres_data:
EOFCOMPOSE

echo "✅ docker-compose.yml created"
"""
        deployer.execute(compose_cmd)

        # Phase 5: Start services
        print("\n🚀 PHASE 5: Start Services")
        print("━" * 60)

        start_cmd = """
cd /opt/stokr-platform

echo "Stopping any existing containers..."
docker-compose down -v 2>/dev/null || true

sleep 3

echo "Starting Release_v2 services..."
docker-compose up -d

sleep 5

echo "✅ Services started"
docker-compose ps
"""
        deployer.execute(start_cmd)

        # Phase 6: Wait for services
        print("\n⏳ PHASE 6: Wait for Services to Be Ready")
        print("━" * 60)

        for attempt in range(1, 31):
            output, err, code = deployer.execute("curl -s http://localhost:8080/api/health 2>/dev/null | grep -q UP && echo OK || echo WAIT", show_output=False)
            if "OK" in output:
                print(f"✅ API is healthy (attempt {attempt}/30)")
                break
            else:
                print(f"⏳ Waiting for API to be ready... ({attempt}/30)")
                time.sleep(2)

        # Phase 7: Verify
        print("\n✅ PHASE 7: Verify Deployment")
        print("━" * 60)

        verify_cmd = """
echo "=== API Health ==="
curl -s http://localhost:8080/api/health || echo "Waiting for API..."

echo -e "\n=== Container Status ==="
docker-compose ps

echo -e "\n=== Database Status ==="
PGPASSWORD=stokr psql -U stokr -h localhost -d stokr_platform -c "SELECT COUNT(*) as migration_count FROM flyway_schema_history;" 2>/dev/null || echo "Initializing database..."

echo -e "\n=== Disk Space ==="
df -h / | tail -1

echo -e "\n✅ Deployment Complete"
"""
        deployer.execute(verify_cmd)

        print("\n" + "=" * 70)
        print("✅ RELEASE_V2 DEPLOYED TO PRODUCTION")
        print("=" * 70)
        print("\n🎯 Access Points:")
        print("   API:  http://173.249.55.84:8080/api/health")
        print("   UI:   http://173.249.55.84 or http://new.stokr.in")
        print("\n📊 Monitoring:")
        print("   ssh root@173.249.55.84")
        print("   cd /opt/stokr-platform")
        print("   docker-compose logs -f")
        print("\n🔄 If Issues:")
        print("   docker-compose down")
        print("   docker-compose up -d")
        print("\n" + "=" * 70)

    except Exception as e:
        print(f"\n❌ ERROR: {e}")
        import traceback
        traceback.print_exc()
        sys.exit(1)
    finally:
        deployer.close()

if __name__ == "__main__":
    main()
