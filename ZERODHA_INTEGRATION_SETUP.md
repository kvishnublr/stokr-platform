# 🔗 ZERODHA INTEGRATION SETUP - EXACT GUIDE

**Status**: Ready to integrate  
**Date**: 2026-06-08  

---

## 📋 **STEP 1: Get Zerodha API Credentials**

### **On Zerodha (zerodha.com)**

1. **Login to your Zerodha account**
   - Go to https://zerodha.com
   - Login with your credentials

2. **Go to Console**
   - URL: https://console.zerodha.com
   - Left sidebar → Apps → Connected Apps

3. **Create New App**
   ```
   Click: "+ Create App"
   Name: Stokr Platform
   Description: Automated trading with Stokr
   Redirect URL: http://173.249.55.84:8080/api/broker/zerodha/callback
   Permissions: ✓ Read
               ✓ Write (for orders)
   ```

4. **Generate Credentials**
   ```
   You'll get:
   ├─ API KEY (something like: xxxxx1234567890)
   └─ API SECRET (long string)
   
   SAVE THESE! You need them next.
   ```

---

## 📋 **STEP 2: Configure Stokr Server**

### **SSH to Server**

```bash
ssh root@173.249.55.84
```

### **Update Environment Variables**

```bash
# Edit the startup script or create .env file
cat > /app/zerodha.env << 'EOF'
STOKR_ZERODHA_API_KEY=YOUR_API_KEY_HERE
STOKR_ZERODHA_API_SECRET=YOUR_API_SECRET_HERE
STOKR_ZERODHA_REDIRECT_URL=http://173.249.55.84:8080/api/broker/zerodha/callback
STOKR_ZERODHA_FEED_ENABLED=true
STOKR_ZERODHA_POSITIONS_SYNC_ENABLED=true
STOKR_ZERODHA_ORDERS_SYNC_ENABLED=true
EOF
```

### **Restart Application with Zerodha Config**

```bash
# Kill old process
killall -9 java 2>/dev/null || true
sleep 2

# Start with Zerodha credentials
cd /app
nohup java -Xmx2g -Xms1g \
  -Dspring.datasource.url=jdbc:postgresql://localhost:5432/stokr_platform \
  -Dspring.datasource.username=stokr_user \
  -Dspring.datasource.password=stokr_password \
  -Dspring.flyway.mixed=true \
  -Dstokr.zerodha.api-key=${STOKR_ZERODHA_API_KEY} \
  -Dstokr.zerodha.api-secret=${STOKR_ZERODHA_API_SECRET} \
  -Dstokr.zerodha.redirect-url=http://173.249.55.84:8080/api/broker/zerodha/callback \
  -Dstokr.zerodha.feed.enabled=true \
  -Dstokr.zerodha.positions.sync.enabled=true \
  -Dstokr.zerodha.orders.sync.enabled=true \
  -Dstokr.confidence.auto-trade.enabled=true \
  -Dstokr.confidence.auto-trade.execution-mode=SIMULATED \
  -jar stokr-bootstrap-1.0.0-SNAPSHOT.jar > /var/log/stokr-platform.log 2>&1 &

sleep 30

# Verify it started
curl -s http://localhost:8080/health | head -2
```

---

## 📋 **STEP 3: Authenticate from Trader UI**

### **In Stokr Trader (stokr.in/positions)**

1. **Click: Settings** (top right)
2. **Go to: Integrations → Broker Connect**
3. **Select: Zerodha**
4. **Click: Connect**
5. **Authenticate:**
   - Browser opens Zerodha login
   - Login with your Zerodha account
   - Grant permission to Stokr
   - You'll be redirected back to Stokr

### **Success!**
```
You'll see:
✓ Zerodha connected
✓ Positions syncing
✓ Live P&L updating
```

---

## 📊 **STEP 4: Verify Integration**

### **Check Positions Sync**

```bash
# SSH to server
ssh root@173.249.55.84

# Check logs for Zerodha sync
tail -50 /var/log/stokr-platform.log | grep -i "zerodha\|position\|sync"

# Should see:
# ✅ Zerodha position sync started
# ✅ Live feed connected
# ✅ Positions syncing every 1 minute
```

### **In Trader UI**

Go to: **stokr.in/positions**

Should now show:
```
✓ Open Symbols: 8
✓ Gross Notional: ₹XX,XXX
✓ Live positions (8) with P&L
```

---

## 🔄 **WHAT HAPPENS AFTER SETUP**

```
Zerodha Live Feed:
├─ Every tick → Stokr receives price update
├─ Every minute → Positions sync from Zerodha
├─ Every minute → P&L updates in UI
└─ Every exit → Order syncs back to Zerodha

Result:
├─ Trader UI shows real live positions
├─ P&L updates in real-time
├─ Orders visible in both systems
└─ Perfect sync between Zerodha & Stokr
```

---

## ⚙️ **CONFIGURATION OPTIONS**

### **In application.yml**

```yaml
stokr:
  zerodha:
    api-key: ${STOKR_ZERODHA_API_KEY}
    api-secret: ${STOKR_ZERODHA_API_SECRET}
    redirect-url: http://173.249.55.84:8080/api/broker/zerodha/callback
    
    feed:
      enabled: true
      reconnect-interval-ms: 5000
      
    positions:
      sync:
        enabled: true
        interval-ms: 60000  # Sync every 1 minute
        
    orders:
      sync:
        enabled: true
        interval-ms: 30000  # Sync every 30 seconds
        
    market-data:
      enabled: true
      cache-duration-ms: 1000
```

---

## 🔧 **TROUBLESHOOTING**

### **If Positions Still Not Showing**

**Check 1: Verify API Key**
```bash
ssh root@173.249.55.84
tail -100 /var/log/stokr-platform.log | grep -i "error\|invalid"
```

**Check 2: Verify Zerodha Session**
```bash
# Go to Zerodha console
# Check: Connected Apps → Stokr
# If disconnected: Reconnect from Settings → Integrations
```

**Check 3: Check Sync Status**
```bash
curl http://173.249.55.84:8080/api/broker/zerodha/status
# Should return: {"status":"connected","lastSyncTime":"..."}
```

### **If Sync Fails**

```bash
# Restart broker connection
curl -X POST http://173.249.55.84:8080/api/broker/zerodha/reconnect

# Wait 10 seconds
sleep 10

# Check status again
curl http://173.249.55.84:8080/api/broker/zerodha/status
```

---

## ✅ **VERIFICATION CHECKLIST**

```
Before Zerodha Setup:
☐ Zerodha app created with correct redirect URL
☐ API key & secret saved
☐ Server restarted with credentials

After Setup:
☐ Zerodha shows as "Connected" in Settings
☐ Trader UI shows "Live positions (8)"
☐ P&L updating every minute
☐ Orders visible in Zerodha terminal
☐ New signals create orders in Zerodha
☐ Position exits sync back to Zerodha

Final Verification:
☐ curl http://173.249.55.84:8080/api/broker/zerodha/status
   Response: {"status":"connected"}
☐ tail -10 /var/log/stokr-platform.log | grep zerodha
   Shows: No errors, regular sync logs
```

---

## 🎯 **FINAL RESULT**

After setup, you'll have:

```
Trader Workspace (stokr.in/positions):
├─ Live positions from Zerodha
├─ Real-time P&L
├─ Order syncing
└─ Perfect mirror of account

Backend (173.249.55.84):
├─ Automatic trading in SIMULATED mode
├─ Orders synced to Zerodha (for viewing)
├─ Positions tracked in both systems
└─ Everything in sync!
```

---

## 📞 **NEXT STEPS**

1. **Get Zerodha API credentials** (10 min)
2. **Configure server** (5 min)
3. **Authenticate in UI** (2 min)
4. **Verify sync** (5 min)

**Total: ~20 minutes to full Zerodha integration!**

---

**Date**: 2026-06-08  
**Status**: Ready for integration  
**Mode**: SIMULATED (safe to test)
