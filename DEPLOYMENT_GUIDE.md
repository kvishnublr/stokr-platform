# DEPLOYMENT GUIDE - Contabo Server (173.249.55.84)

## PRE-DEPLOYMENT CHECKLIST

```bash
# On Contabo server:
ssh -i your_key root@173.249.55.84

# 1. Backup current database
pg_dump -U postgres -h localhost stokr_platform > /backup/stokr_platform_$(date +%Y%m%d_%H%M%S).sql

# 2. Stop current running service
systemctl stop stokr-platform
# or
pkill -f "java.*stokr-bootstrap"

# 3. Check disk space (need ~500MB for build)
df -h /
```

## DEPLOYMENT STEPS

```bash
cd /opt/stokr-platform

# 1. Pull latest Release_v1 branch
git fetch origin
git checkout Release_v1
git pull origin Release_v1

# 2. Verify commits pulled correctly
git log --oneline -10
# Should show: b73e1c0 PHASES 4-9
# Should show: 3568fd0 PHASE 3
# Should show: 73232bb PHASE 2
# Should show: bc7f627 PHASE 1 (FINAL)

# 3. Build Maven project
cd stokr-bootstrap
mvn clean install -DskipTests -X 2>&1 | tee build.log

# Check for BUILD SUCCESS
tail -20 build.log | grep -i "BUILD SUCCESS"

# 4. Deploy JAR to execution directory
cp stokr-bootstrap/target/stokr-bootstrap-1.0.0-SNAPSHOT.jar /opt/stokr-platform/app/stokr-platform.jar

# 5. Start service with environment variables
export STOKR_SIGNAL_QUALITY_MIN_RR=1.5
export STOKR_SIGNAL_COOLDOWN_SECONDS=300
export DB_HOST=localhost
export DB_PORT=5432
export DB_NAME=stokr_platform
export DB_USER=postgres
export DB_PASSWORD=root123
export REDIS_HOST=localhost
export RABBITMQ_HOST=localhost

java -jar /opt/stokr-platform/app/stokr-platform.jar > /var/log/stokr-platform.log 2>&1 &

# 6. Verify service is running
sleep 10
curl http://localhost:8080/actuator/health
# Should return: {"status":"UP"}

# 7. Tail logs to verify startup
tail -50 /var/log/stokr-platform.log | grep -i "started\|error\|warn"
```

## VERIFICATION COMMANDS

```bash
# Check if service started correctly
ps aux | grep stokr-platform
# Should show: java -jar /opt/stokr-platform/app/stokr-platform.jar

# Check port 8080 is listening
netstat -tlnp | grep 8080

# Check database connectivity
psql -U postgres -h localhost -d stokr_platform -c "SELECT COUNT(*) FROM strategy_signals;"

# Check if quality gate service is loaded
grep -i "quality_gate" /var/log/stokr-platform.log | head -5

# Check if reconciliation scheduler started
grep -i "reconciliation" /var/log/stokr-platform.log | head -5
```

## ROLLBACK (IF NEEDED)

```bash
# Stop service
systemctl stop stokr-platform

# Restore previous backup
psql -U postgres -h localhost stokr_platform < /backup/stokr_platform_YYYYMMDD_HHMMSS.sql

# Start previous version
systemctl start stokr-platform

# Verify
curl http://localhost:8080/actuator/health
```

---

## POST-DEPLOYMENT ANALYSIS

Once deployed, run these PostgreSQL queries to analyze 6 days of performance:

See: PERFORMANCE_ANALYSIS.sql
