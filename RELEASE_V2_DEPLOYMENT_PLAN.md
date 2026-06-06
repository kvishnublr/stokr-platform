# Release_v2 Production Deployment Plan

**Target:** new.stokr.in (173.249.55.84)  
**Branch:** Release_v2  
**Status:** Ready for Execution  
**Risk Level:** Medium (Production Deployment)

---

## 📋 Deployment Checklist

### **Pre-Deployment**
- [ ] Backup current production database
- [ ] Backup current configuration
- [ ] Verify all endpoints are accessible
- [ ] Confirm network connectivity to 173.249.55.84
- [ ] Have rollback plan ready

### **Build Phase**
- [ ] Clone/checkout Release_v2 branch
- [ ] Build stokr-ui
- [ ] Build backend services
- [ ] Docker build images
- [ ] Push to registry

### **Deployment Phase**
- [ ] Deploy UI to new.stokr.in
- [ ] Deploy backend services to new.stokr.in
- [ ] Update .env configuration
- [ ] Run migrations
- [ ] Start services

### **Verification Phase**
- [ ] Check UI loads at new.stokr.in
- [ ] Test login flow
- [ ] Test trading features
- [ ] Test admin features
- [ ] Verify database connectivity
- [ ] Verify WebSocket connections
- [ ] Check logs for errors

### **Post-Deployment**
- [ ] Monitor system for 24 hours
- [ ] Confirm all features working
- [ ] Document any issues
- [ ] Notify stakeholders

---

## 🔧 Detailed Deployment Steps

### **Step 1: Prepare Release_v2 (Current Branch)**
```bash
cd C:\Users\itsvi\Desktop\work_new\stokr-platform
git checkout Release_v2
git pull origin Release_v2
```

### **Step 2: Build UI**
```bash
cd stokr-ui
npm ci
npm run build
# Output: dist/ folder ready for deployment
```

### **Step 3: Build Backend**
```bash
cd ..
# Assuming Maven is configured
mvn clean package -DskipTests
# Or use docker build
```

### **Step 4: Docker Images**
```bash
# UI Docker image
cd stokr-ui
docker build -t new.stokr.in/stokr-ui:v2 .
docker push new.stokr.in/stokr-ui:v2

# Backend Docker image
docker build -t new.stokr.in/stokr-api:v2 -f stokr-bootstrap/Dockerfile .
docker push new.stokr.in/stokr-api:v2
```

### **Step 5: Deploy to Production**
```bash
# SSH into production server
ssh user@173.249.55.84

# Pull latest images
docker pull new.stokr.in/stokr-ui:v2
docker pull new.stokr.in/stokr-api:v2

# Update docker-compose.yml with new image tags
# Deploy with docker compose
docker compose -f docker-compose.prod.yml up -d

# Wait for services to start
sleep 30

# Verify services are running
docker ps
```

### **Step 6: Health Checks**
```bash
# Check UI
curl https://new.stokr.in

# Check API
curl https://new.stokr.in/api/health

# Check WebSocket
# (via UI)

# Check database
# (query via API)
```

---

## ⚠️ Important Considerations

### **Database**
- v2 uses same database as v1 (backward compatible)
- May need migrations (should be automated)
- Backup existing database first

### **Configuration**
- .env.local points to new.stokr.in ✅
- All endpoints configured for v2
- SSL certificates in place

### **Rollback Plan**
- Keep v1 running as fallback
- Tag all Docker images with version
- Document rollback steps
- Test rollback procedure

### **Network Requirements**
- Connectivity to 173.249.55.84:22 (SSH)
- Docker registry access
- PostgreSQL access
- RabbitMQ access
- Redis access

---

## 📊 Deployment Timeline

| Phase | Duration | Steps |
|-------|----------|-------|
| **Pre-Deploy** | 10 min | Verify access, backups |
| **Build** | 15 min | Build UI & backend |
| **Docker** | 10 min | Build & push images |
| **Deploy** | 10 min | Pull & start services |
| **Verify** | 15 min | Run health checks |
| **Monitor** | 30 min | Initial monitoring |
| **TOTAL** | ~90 min | **Full deployment** |

---

## ✅ Success Criteria

All of these must pass before declaring success:

```
UI Layer:
  ✅ https://new.stokr.in loads
  ✅ Login page renders
  ✅ Authentication works
  ✅ Dashboard loads
  ✅ No console errors

API Layer:
  ✅ /api/health returns 200
  ✅ /api/auth/* endpoints work
  ✅ /api/portfolio/* endpoints work
  ✅ /api/orders/* endpoints work
  ✅ All admin endpoints accessible

WebSocket:
  ✅ /ws connections establish
  ✅ Real-time data streams
  ✅ No connection drops

Database:
  ✅ PostgreSQL connected
  ✅ Tables intact
  ✅ Data accessible
  ✅ Migrations applied

System:
  ✅ All services running
  ✅ No critical errors in logs
  ✅ CPU/Memory normal
  ✅ Disk space adequate
```

---

## 🚨 Rollback Procedure (If Needed)

If issues occur:

```bash
# 1. Stop v2 services
docker compose -f docker-compose.prod.yml down

# 2. Restart v1 services
docker compose -f docker-compose.prod.yml.v1 up -d

# 3. Verify v1 is working
curl https://new.stokr.in

# 4. Investigate issue
# Document error
# Fix issue
# Try again
```

---

## 📞 Contact Points

- **Deployment Contact:** User (itsvi)
- **Emergency Rollback:** Immediate
- **Monitoring Period:** 24 hours post-deployment

---

## ⚠️ IMPORTANT CONFIRMATION NEEDED

Before proceeding with deployment, please confirm:

```
1. ✅ Backup of current production taken
2. ✅ Rollback plan reviewed and ready
3. ✅ Team notified of deployment
4. ✅ Monitoring tools ready
5. ✅ Authority to deploy to production granted
```

---

## 🎯 Status

```
Current State:  Release_v2 ready on Release_v2 branch
Target State:   Release_v2 running on new.stokr.in
Estimated Time: 90 minutes
Risk Level:     MEDIUM (production deployment)
Status:         ⏳ AWAITING CONFIRMATION
```

---

**ACTION REQUIRED:** 

Confirm you want to proceed with Release_v2 deployment to production (new.stokr.in).

If YES → I will execute the deployment plan step by step and report progress.
If NO → Please clarify what adjustments needed before deploying.
