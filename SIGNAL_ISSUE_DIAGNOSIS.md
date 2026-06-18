# Signal Generation Issue - Root Cause Analysis

## 📊 Current Status (as of 2026-06-18 11:38 AM IST)

### What's Working:
✅ Database running (stokr-postgres)
✅ Frontend running (stokr-ui on port 3000)
✅ Nginx proxy running (stokr-lite-nginx on port 8082)
✅ 3 active deployments in database
✅ 5 strategies enabled
✅ Trader configs exist for 4 users

### What's Broken:
❌ **stokr-lite backend NOT RUNNING** - This is the ROOT CAUSE
❌ No signal generation happening during market hours
❌ Only 3 signals generated today (all outside market hours at 3-4 AM)
❌ Signals stuck in GENERATED/ENSEMBLE_FILTERED status (not executed)

## 🔍 Root Cause

**Architecture Mismatch:**
- `stokr-api` container = OLD monolith (stokr-platform) on port 8080
- `stokr-lite-nginx` = Trying to proxy to port 8070 (stokr-lite backend)
- **Port 8070 has NO service running!**

**Evidence:**
1. No scheduler logs from `SchedulerService`, `ExecutionEngine`, or `SignalProcessor`
2. stokr-api logs show OLD monolith schedulers (OrphanMonitorScheduler, PositionReconciliationService)
3. stokr-lite-nginx config shows `proxy_pass http://localhost:8070`
4. No container exposing port 8070 in `docker ps`

## 📋 Solution

### Option 1: Deploy stokr-lite backend (RECOMMENDED)
Build and deploy the stokr-lite backend container to port 8070

### Option 2: Update nginx to use stokr-api
Change stokr-lite-nginx to proxy to stokr-api:8080 (but this uses old code)

## 🚀 Recommended Fix: Deploy stokr-lite Backend

1. Build stokr-lite backend Docker image
2. Create docker-compose service for stokr-lite-backend
3. Start the service on port 8070
4. Verify scheduler logs show signal generation
5. Monitor signal generation during market hours (9:15 AM - 3:30 PM IST)

## 📝 Additional Issues Found

1. Duplicate deployments: User 4 has TWO PAPER deployments for strategy 1
2. No market data universe configuration found
3. Signal execution pipeline not processing GENERATED signals
