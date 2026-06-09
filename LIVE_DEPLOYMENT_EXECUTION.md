# 🚀 LIVE DEPLOYMENT EXECUTION GUIDE
## Real-Time Deployment Monitoring

**Status**: READY TO DEPLOY  
**Commit**: 498ec24d  
**JAR**: 90MB (verified)  
**Target**: 173.249.55.84  
**Timeline**: 08:00-09:25 UTC (2026-06-09)

---

# PHASE 1: DEPLOYMENT (08:15-08:25 UTC)

## Step 1: Build Docker Image

```bash
cd /path/to/stokr-platform

# Build image from commit 498ec24d
docker build -t stokr-api:498ec24d .

# Verify build
docker images | grep stokr-api
```

**Expected Output**:
```
REPOSITORY   TAG          IMAGE ID       CREATED        SIZE
stokr-api    498ec24d     [hash]         [time]         1.2GB
```

## Step 2: Stop Current Container

```bash
docker stop stokr-api
docker ps | grep stokr-api
# (should be empty)
```

## Step 3: Start New Container

```bash
docker-compose -f docker-compose.yml up -d
docker logs -f stokr-api
# Wait for "started in X ms"
```

## Step 4: Verify Health

```bash
curl -s http://localhost:8080/actuator/health | jq '.'
# Expected: {"status":"UP"}
```

---

# PHASE 2: VERIFICATION (08:30 UTC)

```bash
bash REMOTE_VERIFICATION_SCRIPT.sh
```

**Expected**: ALL CHECKS PASSED ✅

---

# PHASE 3: GO/NO-GO GATE (08:45 UTC)

All 10 checks must PASS:
1. ☑ Git: 498ec24d
2. ☑ Docker: RUNNING
3. ☑ Health: UP
4. ☑ Cooldown: 30000
5. ☑ Cluster: enabled
6. ☑ Max Entries: 2
7. ☑ Logs: Clean
8. ☑ Broker: Active
9. ☑ Database: Connected
10. ☑ Verification: PASS

---

# PHASE 4: TEST TRADE (09:15 UTC)

## Monitor Logs

```bash
docker logs -f stokr-api | grep -i "SBIN"
```

## Verify Database

```sql
SELECT symbol, entry_price, outcome_status
FROM strategy_signals
WHERE symbol = 'SBIN' AND test_trade = true
ORDER BY created_at DESC LIMIT 1;
```

## Success Criteria

✅ Signal created at 09:15  
✅ Entry executed within 5 sec  
✅ Exit executed within 5 min  
✅ Outcome = CLOSED  
✅ No ERROR logs

---

# ROLLBACK (if needed)

```bash
docker stop stokr-api
git checkout 01baad21
docker build -t stokr-api:backup .
docker-compose up -d
```

Recovery time: 5-15 minutes

