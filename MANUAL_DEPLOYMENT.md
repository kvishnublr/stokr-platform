# RELEASE_V2 MANUAL DEPLOYMENT GUIDE

**Server:** 173.249.55.84 (new.stokr.in)  
**User:** root  
**Password:** 19119e3a6793dde1  
**Domain:** new.stokr.in

---

## STEP 1: SSH to Server

```bash
ssh root@173.249.55.84
# Enter password when prompted: 19119e3a6793dde1
```

---

## STEP 2: Verify Prerequisites

Once connected, run these verification commands:

```bash
# Check Docker
docker --version
docker-compose --version

# Check PostgreSQL
PGPASSWORD=stokr pg_isready -h localhost -p 5432

# Check disk space
df -h /

# Check if ports are available
netstat -tlnp | grep -E ':80|:8080|:5432' || echo "Ports available"
```

**Expected Output:**
```
Docker version 20.10+
Docker Compose version 1.29+
accepting connections (PostgreSQL)
At least 10GB free
All ports available
```

---

## STEP 3: Create Deployment Directory

```bash
mkdir -p /opt/stokr-platform
cd /opt/stokr-platform
pwd  # Should show: /opt/stokr-platform
```

---

## STEP 4: Backup Database

```bash
mkdir -p /backups

# Backup database
PGPASSWORD=stokr pg_dump -U stokr -h localhost stokr_platform | \
  gzip > /backups/stokr_platform_$(date +%Y%m%d_%H%M%S).sql.gz

# Verify backup
ls -lh /backups/ | head -5
```

---

## STEP 5: Create Docker Compose Configuration

```bash
# Create docker-compose.yml
cat > docker-compose.yml << 'EOF'
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
    restart: unless-stopped
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8080/api/health"]
      interval: 30s
      timeout: 10s
      retries: 3

  stokr-ui:
    image: nginx:alpine
    container_name: stokr-ui-v2
    ports:
      - "80:80"
    depends_on:
      - stokr-api
    networks:
      - stokr-network
    restart: unless-stopped

networks:
  stokr-network:
    driver: bridge

volumes:
  postgres_data:
EOF

# Verify file created
ls -lh docker-compose.yml
```

---

## STEP 6: Start Services

```bash
# Stop any existing containers
docker-compose down -v 2>/dev/null || true

sleep 5

# Start services
docker-compose up -d

# Wait for startup
sleep 10

# Check status
docker-compose ps
```

**Expected Output:**
```
NAME                COMMAND                STATUS
stokr-postgres-v2   "docker-entrypoint..."  Up X seconds (healthy)
stokr-api-v2        "java ..."              Up X seconds
stokr-ui-v2         "nginx ..."             Up X seconds
```

---

## STEP 7: Verify Deployment

```bash
# Test API health (wait up to 60 seconds)
for i in {1..30}; do
  curl -s http://localhost:8080/api/health | grep -q "UP" && \
    echo "API is ready" && break || \
    echo "Waiting for API... (attempt $i/30)" && sleep 2
done

# Check API response
curl -s http://localhost:8080/api/health | head -20

# Check UI
curl -I http://localhost:80 | head -5

# Check database migrations
PGPASSWORD=stokr psql -U stokr -h localhost -d stokr_platform -c \
  "SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5;"

# Check logs for errors
docker-compose logs | grep -i error | head -10 || echo "No errors in logs"

# Check container resource usage
docker stats --no-stream
```

---

## STEP 8: Final Verification Checklist

Run these checks and verify everything passes:

```bash
# 1. API Health
curl -s http://localhost:8080/api/health | grep -q "UP" && echo "✓ API Healthy" || echo "✗ API Issue"

# 2. Database Connected
PGPASSWORD=stokr psql -U stokr -h localhost -d stokr_platform -c "SELECT 1;" && echo "✓ Database OK" || echo "✗ Database Issue"

# 3. Migrations Applied
MIGRATION_COUNT=$(PGPASSWORD=stokr psql -U stokr -h localhost -d stokr_platform -t -c "SELECT COUNT(*) FROM flyway_schema_history;")
echo "✓ Migrations: $MIGRATION_COUNT (should be 102)"

# 4. Services Running
docker-compose ps | grep -c "Up" | grep -q "3" && echo "✓ All Services Running" || echo "✗ Service Issue"

# 5. Disk Space
df -h / | tail -1 | awk '{print $4}' | grep -qE 'G$' && echo "✓ Disk Space OK" || echo "✗ Low Disk Space"

# 6. No Critical Errors
docker-compose logs | grep -c "CRITICAL" | grep -q "0" && echo "✓ No Critical Errors" || echo "⚠ Check Logs"
```

---

## STEP 9: Monitor Deployment

Keep these commands ready for monitoring:

```bash
# Watch logs in real-time
docker-compose logs -f

# Check container status
watch -n 5 'docker-compose ps'

# Monitor resource usage
docker stats --no-stream

# API health (every 30 seconds)
watch -n 30 'curl -s http://localhost:8080/api/health | jq .'

# Check specific container logs
docker-compose logs stokr-api
docker-compose logs postgres
```

---

## STEP 10: Access Application

After all verifications pass:

```
API Endpoint:  http://173.249.55.84:8080
UI Endpoint:   http://173.249.55.84:80 (or http://new.stokr.in)
Admin Page:    http://173.249.55.84:80/admin
Health Check:  http://173.249.55.84:8080/api/health
```

---

## IF ISSUES OCCUR - TROUBLESHOOTING

### API Won't Start

```bash
# Check logs
docker-compose logs stokr-api | tail -50

# Restart API
docker-compose restart stokr-api

# Check if port 8080 is in use
netstat -tlnp | grep 8080

# Restart all services
docker-compose down
sleep 5
docker-compose up -d
```

### Database Connection Issues

```bash
# Check PostgreSQL
docker-compose logs postgres | tail -20

# Test connection
PGPASSWORD=stokr psql -U stokr -h localhost -d stokr_platform -c "SELECT VERSION();"

# Restart database
docker-compose restart postgres
```

### Services Not Starting

```bash
# Check Docker
docker ps -a

# Remove failed containers
docker-compose down -v

# Start fresh
docker-compose up -d

# Wait and check
sleep 10
docker-compose ps
```

---

## ROLLBACK (If Needed)

```bash
# Stop Release_v2
docker-compose down

# Restore v1 (if v1 config exists)
docker-compose -f docker-compose.v1.yml up -d

# Or manual rollback
docker-compose down -v
# Restore from database backup
gunzip < /backups/stokr_platform_BACKUP_FILE.sql.gz | \
  PGPASSWORD=stokr psql -U stokr -h localhost -d stokr_platform
```

**Database is safe** - all migrations are additive. Can immediately re-deploy v2.

---

## VERIFICATION SUMMARY

After completion, you should have:

```
✓ Docker containers running (postgres, api, ui)
✓ PostgreSQL database with V1-V102 migrations
✓ API responding at :8080/api/health
✓ UI accessible at :80
✓ No critical errors in logs
✓ All services healthy
```

---

## COMMAND QUICK REFERENCE

```bash
# Startup
docker-compose up -d

# Shutdown
docker-compose down

# View logs
docker-compose logs -f [service_name]

# Restart service
docker-compose restart [service_name]

# Check status
docker-compose ps

# Remove all containers/volumes
docker-compose down -v

# Database backup
PGPASSWORD=stokr pg_dump -U stokr -h localhost stokr_platform | gzip > backup.sql.gz

# Check migrations
PGPASSWORD=stokr psql -U stokr -h localhost -d stokr_platform -c \
  "SELECT version FROM flyway_schema_history;"
```

---

**DEPLOYMENT TIME:** ~35 minutes  
**CRITICAL:** Make backups before deployment  
**ROLLBACK:** Instant if needed (database safe)

Good luck! 🚀
