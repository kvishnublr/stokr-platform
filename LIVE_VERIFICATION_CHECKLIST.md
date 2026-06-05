# ✅ LIVE SYSTEM VERIFICATION CHECKLIST

**Server**: 173.249.55.84:8080  
**Date**: 2026-06-05  
**Purpose**: Verify all systems working for smooth trading

---

## 🔍 RUN THESE TESTS ON PRODUCTION SERVER

### **Test 1: Container Status**
```bash
ssh root@173.249.55.84

docker ps | grep stokr
docker logs stokr-platform-api | tail -20
```

**Expected Output:**
```
✅ Container running
✅ Logs show: "✅ All critical systems are operational"
❌ No: "ERROR", "CRITICAL", "NullPointerException"
```

---

### **Test 2: Health Check Endpoint**
```bash
curl -s http://localhost:8080/health | jq .
```

**Expected Response:**
```json
{
  "status": "UP",
  "database": {
    "status": "UP",
    "connection": "OK",
    "pool_active": 2,
    "pool_idle": 18,
    "pool_total": 20
  },
  "livenessState": "LIVE",
  "readinessState": "READY"
}
```

**🚨 BLOCKERS:**
- ❌ status ≠ "UP" → Database down
- ❌ database.status ≠ "UP" → Database unreachable
- ❌ readinessState ≠ "READY" → App not ready for traffic

---

### **Test 3: Admin Login**
```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"principal":"admin@stokr.local","password":"password123"}' | jq .
```

**Expected Response:**
```json
{
  "id": "...",
  "email": "admin@stokr.local",
  "accessToken": "eyJ...",
  "refreshToken": "...",
  "expiresInSeconds": 900
}
```

**🚨 BLOCKERS:**
- ❌ 401 Unauthorized → Wrong password
- ❌ 500 error → JWT service broken
- ❌ No accessToken → Auth system broken

---

### **Test 4: Confidence Scores**
```bash
curl -s http://localhost:8080/api/confidence-strategy/latest-scores?limit=5 | jq .
```

**Expected Response:**
```json
[
  {
    "symbol": "SBIN",
    "confidence_score": 75,
    "timestamp": "2026-06-05T12:30:00Z"
  },
  ...
]
```

**🚨 BLOCKERS:**
- ❌ Empty array [] → No confidence calculation running
- ❌ 500 error → Database query failed
- ❌ Old timestamps (>5 min) → Calculator not running

---

### **Test 5: Today's Signal Count**
```bash
curl -s http://localhost:8080/api/confidence-strategy/today/signal-count | jq .
```

**Expected Response:**
```json
{
  "threshold60": 250,
  "threshold70": 150,
  "threshold80": 50,
  "threshold90": 10,
  "timestamp": "2026-06-05T12:35:00Z"
}
```

**🚨 BLOCKERS:**
- ❌ All zeros (0, 0, 0, 0) → No signals generated
- ❌ 500 error → Generator service broken
- ❌ Timestamp >10 min old → Generator not running

---

### **Test 6: Dashboard Stats**
```bash
curl -s http://localhost:8080/api/confidence-strategy/dashboard/stats | jq .
```

**Expected Response:**
```json
{
  "activeSymbols": 100,
  "lastUpdate": "2026-06-05T12:30:00Z",
  "configuredTraders": 1
}
```

**🚨 BLOCKERS:**
- ❌ activeSymbols = 0 → No Nifty 100 stocks configured
- ❌ lastUpdate >10 min old → System not calculating
- ❌ configuredTraders = 0 → No traders configured

---

### **Test 7: JSON Validation** ⭐ NEW FIX
```bash
# This should FAIL with 400 (malformed JSON - missing quotes)
curl -X POST http://localhost:8080/api/confidence-strategy/config \
  -H "Content-Type: application/json" \
  -d '{traderId:"550e8400-e29b-41d4-a716-446655440000",threshold:70}' | jq .
```

**Expected Response:**
```json
{
  "status": "fail",
  "message": "Invalid JSON format. All field names must be quoted.",
  "data": {
    "code": "INVALID_JSON"
  }
}
```

**Status Code: 400 Bad Request**

**🚨 BLOCKERS:**
- ❌ Returns 500 → JSON validation not working
- ❌ Returns 200 → Accepting invalid JSON

---

### **Test 8: Valid Trader Config** ⭐ NEW FIX
```bash
# This should SUCCEED with 200 (valid JSON)
curl -X POST http://localhost:8080/api/confidence-strategy/config \
  -H "Content-Type: application/json" \
  -d '{"traderId":"550e8400-e29b-41d4-a716-446655440000","threshold":70}' | jq .
```

**Expected Response:**
```json
{
  "id": 1,
  "traderId": "550e8400-e29b-41d4-a716-446655440000",
  "minConfidenceThreshold": 70,
  "strategyName": "CONFIDENCE_BASED_70",
  "enabled": true
}
```

**Status Code: 200 OK**

**🚨 BLOCKERS:**
- ❌ Returns 400 → Valid JSON rejected
- ❌ Returns 500 → Database insert failed
- ❌ No strategyName → Config creation broken

---

### **Test 9: Get Trader Signals**
```bash
curl -s http://localhost:8080/api/confidence-strategy/signals/above/70 | jq 'length'
```

**Expected Response:**
```
150
```

**🚨 BLOCKERS:**
- ❌ Returns 0 → No signals for threshold 70
- ❌ 500 error → API endpoint broken
- ❌ Empty array → Signals not persisting

---

### **Test 10: Database Connection**
```bash
psql -h localhost -U postgres -d stokr_platform -c \
"SELECT COUNT(*) as confidence_scores FROM confidence_scores WHERE timestamp > NOW() - INTERVAL '5 minutes';"
```

**Expected Response:**
```
 confidence_scores
───────────────────
               100
```

**🚨 BLOCKERS:**
- ❌ Returns 0 → No scores calculated in last 5 min
- ❌ Connection refused → Database down
- ❌ Permission denied → Database credentials wrong

---

## 📊 SUMMARY CHECKLIST

Run all tests and mark:

```
□ Test 1: Container running
□ Test 2: /health returns UP
□ Test 3: Admin login works
□ Test 4: Confidence scores > 0
□ Test 5: Signal count > 0
□ Test 6: Dashboard stats correct
□ Test 7: JSON validation rejects bad JSON (400)
□ Test 8: JSON validation accepts valid JSON (200)
□ Test 9: Trader signals exist
□ Test 10: Database connected
```

---

## 🚨 CRITICAL BLOCKERS FOR SMOOTH TRADING

| Blocker | Impact | Fix |
|---------|--------|-----|
| /health returns error | App not operational | Restart container |
| Admin login fails | Cannot access system | Check DB connection |
| No confidence scores | Signals not generating | Check Phase 1 running |
| No signals count | Signal generation broken | Check generator service |
| JSON validation broken | API crashes on bad input | Redeploy with latest JAR |
| DB connection down | All features broken | Restart PostgreSQL |
| Pool starvation detected | Connection exhausted | Increase pool size or restart |

---

## ✅ ALL CLEAR FOR TRADING

If ALL 10 tests pass ✅, system is ready for:
- ✅ Trader login
- ✅ Signal generation
- ✅ Signal delivery
- ✅ Live trading

---

## 🔧 COMMON ISSUES & QUICK FIXES

### Issue: Container not running
```bash
docker logs stokr-platform-api
docker restart stokr-platform-api
```

### Issue: No confidence scores
```bash
# Check if Phase 1 services running
curl http://localhost:8080/api/confidence-strategy/test/calculate-now
# Wait 60 seconds, recheck
```

### Issue: Startup validation failed
```bash
# Check database
psql -h localhost -U postgres -d stokr_platform -c "SELECT 1"
# If fails, restart PostgreSQL
systemctl restart postgresql
```

### Issue: JSON validation not working
```bash
# Redeploy with latest code
docker pull stokr-platform-api:latest
docker rm stokr-platform-api
docker run -d --restart always -p 8080:8080 stokr-platform-api:latest
```

---

## 📞 IF ISSUES FOUND

1. Check container logs: `docker logs stokr-platform-api`
2. Check database: `psql -d stokr_platform -c "SELECT 1"`
3. Check system clock: `date` vs `docker exec stokr-platform-api date`
4. Restart container: `docker restart stokr-platform-api`
5. If still broken, redeploy with latest JAR (commit 38f8b5a)

---

**Ready to trade when all tests pass! ✅**
