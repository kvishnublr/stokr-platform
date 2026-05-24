# Deployment Troubleshooting Guide

## Current Issue: Application Failed to Start

The deployment error shows:
```
Connection to 127.0.0.1:5432 refused. Check that the hostname and port is correct 
and that the postmaster is accepting TCP/IP connections.
```

This means **PostgreSQL is not running** on the Contabo server.

## Quick Fix

SSH into your Contabo server and run:

```bash
cd /path/to/stokr-platform
./health-check.sh restart
```

This will:
1. Check status of all services (postgres, redis, rabbitmq, api, ui)
2. Restart any unhealthy services
3. Wait for them to stabilize
4. Report overall health

## Full Stack Restart (If Above Doesn't Work)

```bash
cd /path/to/stokr-platform
./health-check.sh full-restart
```

This performs a complete restart of all services:
- Stops all containers gracefully
- Starts all services in dependency order
- Waits for health checks to pass
- Preserves all data in persistent volumes

**Note:** Full restart takes ~2-3 minutes. Your data is preserved.

## Understanding the Issue

### Why This Happens

1. **Server restart** - If the Contabo server was rebooted, Docker containers don't auto-start
2. **Deploy with --no-deps** - The original deploy.sh used `--no-deps` which skips dependency services
3. **Missing .env file** - If .env file is not present, services may fail to start
4. **Volume issues** - PostgreSQL data volume might be inaccessible

### Why deploy.sh Failed Silently

The original deploy.sh deployed only the API without ensuring:
- PostgreSQL was running
- Redis was running
- RabbitMQ was running
- Dependent services were healthy

**Fixed in latest deploy.sh** - Now it automatically ensures all dependencies are running before deploying API/JAR.

## Service Status Commands

Check individual service status:

```bash
# Check PostgreSQL
docker exec stokr-postgres pg_isready -U stokr -d stokr_platform

# Check Redis
docker exec stokr-redis redis-cli ping

# Check RabbitMQ
docker exec stokr-rabbitmq rabbitmq-diagnostics -q ping

# Check API health
curl http://localhost:8080/actuator/health

# Check UI
curl http://localhost:3000

# View all containers
docker compose ps

# View logs
docker logs stokr-api -f        # API logs
docker logs stokr-postgres -f   # Database logs
docker logs stokr-redis -f      # Cache logs
docker logs stokr-rabbitmq -f   # Message broker logs
```

## Verifying Deployment Success

Once services are running, verify:

```bash
# 1. API is healthy
curl http://localhost:8080/actuator/health

# 2. Database has migrated
docker exec stokr-postgres psql -U stokr -d stokr_platform -c "SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;"

# 3. API is responding
curl http://localhost:8080/api/health

# 4. UI is accessible
curl http://localhost:3000
```

## Environment Configuration

The deployment relies on `.env` file in project root. Key variables:

```env
# Database
DB_NAME=stokr_platform
DB_USER=stokr
DB_PASSWORD=your_secure_password

# Redis
REDIS_PORT=6379

# RabbitMQ
RABBITMQ_USER=guest
RABBITMQ_PASSWORD=guest

# API Ports
API_PORT=8080
UI_PORT=3000

# Security (IMPORTANT: Change these!)
JWT_SECRET=your_super_long_random_secret_here_min_32_chars

# Broker APIs (Optional)
STOKR_ZERODHA_API_KEY=your_api_key
STOKR_ZERODHA_API_SECRET=your_api_secret

# Email (Optional)
STOKR_MAIL_HOST=smtp.example.com
STOKR_MAIL_PORT=587
STOKR_MAIL_USER=noreply@example.com
STOKR_MAIL_PASSWORD=your_email_password
```

If `.env` is missing, create it with sensible defaults, or use the docker-compose defaults.

## Deployment Flow (Updated)

The CI/CD deployment now follows this flow:

1. **GitHub Actions triggered** - When code pushed to Release_v1
2. **SSH to Contabo server** - Uses DEPLOY_SSH_KEY secret
3. **Pull latest code** - `git pull origin Release_v1`
4. **Run deploy.sh** - Which now:
   - Auto-detects what changed (api/ui/both)
   - **Ensures dependencies are running** (postgres, redis, rabbitmq)
   - **Waits for dependencies to be healthy**
   - Builds Docker image
   - Restarts only the changed service(s)
   - Waits for health check

## Common Deployment Scenarios

### Scenario 1: Fresh Server Setup
```bash
# Fresh server with no containers
./health-check.sh restart
# This will start ALL services for the first time
```

### Scenario 2: Server Restarted
```bash
# Server rebooted, containers stopped
./health-check.sh restart
# This will restart stopped containers
```

### Scenario 3: PostgreSQL Crashed
```bash
# Only database needs restart
docker restart stokr-postgres
# Wait for it to be ready
docker exec stokr-postgres pg_isready -U stokr -d stokr_platform
```

### Scenario 4: Data Corruption/Reset Needed
```bash
# Full reset with data preservation
./health-check.sh full-restart
# Or, to also reset data:
docker compose down -v
docker compose --profile app up -d
# Wait 2-3 minutes for setup
```

### Scenario 5: Manual Deploy Without CI/CD
```bash
# Pull latest code
git pull origin Release_v1

# Quick verification
./health-check.sh

# Deploy using optimized jar mode (faster than full rebuild)
./deploy.sh jar
# Or rebuild docker image
./deploy.sh api ui
```

## Monitoring Deployment

After triggering deployment via GitHub:

```bash
# Watch deployment progress
docker compose ps

# Watch API startup logs
docker logs stokr-api -f

# Monitor health status
watch ./health-check.sh
# (press Ctrl+C to exit watch)
```

## Debugging Failed Deployment

If deployment still fails after running health-check, check logs:

```bash
# Full logs for past hour
docker logs stokr-api --since 1h

# Filter for errors only
docker logs stokr-api 2>&1 | grep -i error

# Check Flyway migrations
docker exec stokr-postgres psql -U stokr -d stokr_platform \
  -c "SELECT * FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 10;"

# Check database connectivity
docker exec stokr-api curl http://stokr-postgres:5432 -v 2>&1 | head -20
```

## Port Conflicts

Default ports used:
- API: 8080
- UI: 3000
- Database: 5432
- Redis: 6379
- RabbitMQ: 5672
- RabbitMQ Admin: 15672

If ports are in use by other services:

```bash
# Find what's using port 8080
lsof -i :8080
# or
netstat -tuln | grep 8080

# Change in docker-compose.yml or .env
# Then restart
./health-check.sh full-restart
```

## Related Scripts

- `deploy.sh` - Main deployment script (handles api/ui/jar modes)
- `health-check.sh` - Service health verification and restart
- `docker-compose.yml` - Service definitions and dependencies
- `.env` - Environment configuration (create if missing)

## Getting Help

For deployment issues, check:

1. **Service logs** - `docker logs <service-name> -f`
2. **Health status** - `./health-check.sh`
3. **Docker status** - `docker compose ps`
4. **Network** - `docker network inspect stokr-platform_default`
5. **Volume** - `docker volume ls | grep stokr`

