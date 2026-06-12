# 🚀 DEPLOY NOW - 5 MINUTE SETUP

**Status**: All code is ready. Just need to execute this on Linux/your server.

---

## 📋 OPTION 1: AUTOMATED (EASIEST)

```bash
# On Linux system:
git clone https://github.com/kvishnublr/stokr-platform.git
cd stokr-platform
git checkout Release_v2

# Make script executable
chmod +x DEPLOY_TO_PRODUCTION.sh

# Deploy (handles everything)
bash DEPLOY_TO_PRODUCTION.sh

# That's it! System goes live.
```

**Time**: ~5 minutes

---

## 📋 OPTION 2: MANUAL (FASTEST IF YOU HAVE JAR)

```bash
# If you already have JAR:
scp stokr-bootstrap-1.0.0-SNAPSHOT.jar root@173.249.55.84:/app/

# On server (173.249.55.84):
docker stop stokr-platform-api 2>/dev/null || true
docker rm stokr-platform-api 2>/dev/null || true

docker run -d \
  --name stokr-platform-api \
  --restart always \
  -p 8080:8080 \
  -e STOKR_CONFIDENCE_AUTO_TRADE_ENABLED=true \
  -e STOKR_CONFIDENCE_AUTO_TRADE_EXECUTION_MODE=SIMULATED \
  -v /app:/app \
  openjdk:21-jdk-slim java -jar /app/stokr-bootstrap-1.0.0-SNAPSHOT.jar

# Verify
curl http://localhost:8080/health
```

**Time**: ~2 minutes

---

## 📋 OPTION 3: DOCKER BUILD

```bash
# Build on Linux
git clone https://github.com/kvishnublr/stokr-platform.git
cd stokr-platform
git checkout Release_v2

docker build -t stokr-api:latest .

# Deploy to server
docker save stokr-api:latest | ssh root@173.249.55.84 'docker load'

# On server
docker run -d --name stokr-platform-api --restart always \
  -p 8080:8080 \
  -e STOKR_CONFIDENCE_AUTO_TRADE_ENABLED=true \
  -e STOKR_CONFIDENCE_AUTO_TRADE_EXECUTION_MODE=SIMULATED \
  stokr-api:latest
```

**Time**: ~7 minutes

---

## ✅ VERIFY DEPLOYMENT

After deployment, check:

```bash
# Health check
curl http://173.249.55.84:8080/health

# Expected response:
{
  "status": "UP",
  "database": {
    "status": "UP",
    "pool_active": 5,
    "pool_idle": 15,
    "pool_total": 20
  },
  "livenessState": "LIVE",
  "readinessState": "READY"
}

# View logs
docker logs -f stokr-platform-api

# Expected logs:
# ✅ Confidence calculator running
# ✅ Signals generated every 2 minutes
# ✅ Auto-trade service active
# ✅ Exit service monitoring
```

---

## 🎯 AFTER DEPLOYMENT

### Minute 1-2:
```
✅ Application starts
✅ Database connected
✅ Health check: UP
```

### Minute 3-4:
```
✅ Confidence scores generated
✅ Signals created for configured thresholds
```

### Minute 5-6:
```
✅ Auto-trade service places orders
✅ Signals converted to trades
✅ Exit service monitoring
```

### Every 2 minutes:
```
✅ New signals generated
✅ New orders placed
✅ Positions monitored
```

---

## 🧪 TEST IT

```bash
# Create test trader config (threshold 50 = more signals)
curl -X POST http://173.249.55.84:8080/api/confidence-strategy/config \
  -H "Content-Type: application/json" \
  -d '{"traderId":"test-uuid-1234","threshold":50}'

# Wait 4 minutes (2 min signal gen + 2 min order placement)

# Check if orders placed
curl http://173.249.55.84:8080/api/confidence-strategy/signals/above/50

# Expected: Array of signals/orders
```

---

## 📊 MONITORING

```bash
# Watch logs in real-time
docker logs -f stokr-platform-api

# Watch for these patterns:
# ✅ "Signal conversion complete"
# ✅ "Order placed from signal"
# ✅ "Exit check complete"

# If you see errors:
# ❌ "Daily signal cap exceeded" → Risk gate working
# ❌ "Max positions exceeded" → Risk gate working
# ❌ "Market closed" → Schedule check working
```

---

## 🔧 IF SOMETHING BREAKS

```bash
# View last 50 logs
docker logs stokr-platform-api | tail -50

# Restart
docker restart stokr-platform-api

# Full rebuild (if JAR corrupted)
docker stop stokr-platform-api
docker rm stokr-platform-api
# Re-run the docker run command above

# Check database connection
docker exec stokr-platform-api curl http://localhost:8080/health
```

---

## ✨ SUMMARY

**All code is 100% ready.**

**Pick an option above based on your setup:**
- Option 1 (Automated): Easiest, handles everything
- Option 2 (Manual): Fastest if JAR exists
- Option 3 (Docker): Best for production

**Time to live**: 2-7 minutes

**Ready to trade**: Immediately after deployment

---

**Latest commit**: bc7f9cfe (Deployment script added)  
**Branch**: Release_v2  
**Status**: 🟢 **READY FOR DEPLOYMENT**

🚀 **EXECUTE ONE OF THE OPTIONS ABOVE TO GO LIVE!**
