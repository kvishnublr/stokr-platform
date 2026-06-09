# 🚀 STOKR PRODUCTION DEPLOYMENT - INSTRUCTIONS FOR TONIGHT

**Status:** READY FOR EXECUTION  
**Target:** Production Server (173.249.55.84)  
**Deployment Type:** Dual Version (EXISTING + NEW Design)  
**Risk Level:** ZERO (Instant Rollback Available)  
**Execution Time:** 30-45 minutes

---

## 📋 PRE-DEPLOYMENT VERIFICATION

Before executing, verify you have these files ready:

```bash
✓ TRADER_PANEL_NATURE_ORGANIC.html         (NEW Trader Panel)
✓ ADMIN_PANEL_NATURE_ORGANIC.html          (NEW Admin Panel)
✓ CONFIG.json                              (Configuration)
✓ nginx-router.conf                        (Router Configuration)
✓ deploy-to-prod.sh                        (Deployment Script)
✓ rollback-prod.sh                         (Rollback Script)
✓ health-check-prod.sh                     (Monitoring Script)
```

**Verify Files Exist:**
```bash
ls -la TRADER_PANEL_NATURE_ORGANIC.html
ls -la ADMIN_PANEL_NATURE_ORGANIC.html
ls -la CONFIG.json
ls -la nginx-router.conf
ls -la *.sh
```

---

## 🔐 SECURITY CHECKS

Before deploying:

- [ ] You are logged in as `root` on local machine
- [ ] SSH key is configured for `root@prod.stokr.in`
- [ ] You have sudo access
- [ ] Network connectivity to prod server is stable
- [ ] VPN is connected (if required)
- [ ] Firewall rules are configured

**Test Connectivity:**
```bash
ping prod.stokr.in
ssh root@prod.stokr.in "echo 'Connected'"
```

Expected: No timeout, quick response

---

## 🚀 DEPLOYMENT STEPS (30-45 MIN)

### STEP 1: Navigate to Deployment Directory (2 min)

```bash
# Go to where deployment files are located
cd /path/to/stokr-platform

# Verify files are here
ls -la deploy-to-prod.sh nginx-router.conf *.html CONFIG.json

# Expected: All files should be listed
```

### STEP 2: Make Scripts Executable (1 min)

```bash
# Make deployment script executable
chmod +x deploy-to-prod.sh
chmod +x rollback-prod.sh
chmod +x health-check-prod.sh

# Verify
ls -la *.sh | grep rwx
```

### STEP 3: Create Deployment Log Directory (1 min)

```bash
# Create log directory locally (optional)
mkdir -p ./deployment-logs

# Verify
ls -la deployment-logs/
```

### STEP 4: Execute Deployment (20-30 min)

**CRITICAL: Execute this carefully and watch the output!**

```bash
# Run deployment script with SUDO
sudo ./deploy-to-prod.sh

# The script will:
# 1. Verify all files exist
# 2. Test SSH connectivity
# 3. Backup existing deployment
# 4. Prepare directories
# 5. Upload NEW design files
# 6. Start NEW design services
# 7. Deploy NGINX router
# 8. Run health checks
# 9. Generate deployment summary

# Expected Output:
# ✓ Pre-deployment checks passed
# ✓ Backup completed
# ✓ Directories prepared
# ✓ NEW Design deployed
# ✓ Services started
# ✓ Router deployed
# ✓ Health checks passed
# ✓ Deployment complete
```

**If you see any ERRORS or WARNINGS:**
```bash
# STOP immediately
# Read error message carefully
# Check deployment-logs/ for details
# Fix issue and retry
# DO NOT PROCEED if there are critical errors
```

---

## ✅ POST-DEPLOYMENT VERIFICATION (5-10 min)

### Step 5A: Check Router Status

```bash
# Test EXISTING design (should work - default)
curl -I "http://prod.stokr.in/trader"
curl -I "http://prod.stokr.in/admin"

# Expected: HTTP 200 OK or similar success response
```

### Step 5B: Check NEW Design Status

```bash
# Test NEW design (should work with ?v=new parameter)
curl -I "http://prod.stokr.in/trader?v=new"
curl -I "http://prod.stokr.in/admin?v=new"

# Expected: HTTP 200 OK or similar success response
```

### Step 5C: Check API Connectivity

```bash
# Verify API can be reached through router
curl -I "http://173.249.55.84:8080/api/health"

# Expected: HTTP 200 OK
```

### Step 5D: Start Monitoring

```bash
# Open new terminal window/tab
# Run health check script in monitor mode
sudo ./health-check-prod.sh

# This will show continuous status updates
# Keep this running while testing tomorrow
```

---

## 📊 WHAT YOU SHOULD SEE

### After Successful Deployment

```
╔════════════════════════════════════════════════════════════════╗
║                    DEPLOYMENT COMPLETE ✓                        ║
╚════════════════════════════════════════════════════════════════╝

EXISTING DESIGN (STABLE):
  ✓ Default URL: https://prod.stokr.in/trader
  ✓ Status: RUNNING
  ✓ Port: 8080 (Trader), 8081 (Admin)

NEW DESIGN (TESTING):
  ✓ Test URL: https://prod.stokr.in/trader?v=new
  ✓ Status: RUNNING
  ✓ Port: 8082 (Trader), 8083 (Admin)

ROUTER:
  ✓ Status: ACTIVE
  ✓ Port: 9090

TESTING INSTRUCTIONS FOR TOMORROW:
  1. Default: https://prod.stokr.in/trader
  2. New Design: https://prod.stokr.in/trader?v=new
  3. Check console: F12 → Console tab
```

---

## 🚨 IF SOMETHING GOES WRONG

### Issue: Deployment Script Fails

**Solution:**
```bash
# 1. Stop the script (Ctrl+C)
# 2. Check the error message carefully
# 3. Read error in deployment log:
cat ./deployment-logs/deployment.log

# Common issues:
# - SSH key not configured
# - Firewall blocking connection
# - Disk space issue
# - Port already in use

# Fix the issue and retry deployment
sudo ./deploy-to-prod.sh
```

### Issue: EXISTING Design Stops Working

**Solution:**
```bash
# Execute IMMEDIATE rollback
sudo ./rollback-prod.sh

# This will:
# ✓ Stop NEW design services
# ✓ Restore EXISTING design as default
# ✓ Zero downtime, zero data loss
# ✓ Users automatically see EXISTING design

# Result: All users back to stable EXISTING design
```

### Issue: NEW Design Not Accessible

**Solution:**
```bash
# Check if services are running
ssh root@prod.stokr.in "pgrep -af 'python3 -m http.server'"

# Check logs
ssh root@prod.stokr.in "tail -50 /var/log/stokr/new/trader.log"

# Restart services manually
ssh root@prod.stokr.in "
  pkill -f 'python3 -m http.server 8082'
  pkill -f 'python3 -m http.server 8083'
  sleep 2
  cd /var/www/stokr/new/trader && nohup python3 -m http.server 8082 > /var/log/stokr/new/trader.log 2>&1 &
  cd /var/www/stokr/new/admin && nohup python3 -m http.server 8083 > /var/log/stokr/new/admin.log 2>&1 &
"

# Test again
curl http://prod.stokr.in/trader?v=new
```

---

## 📝 DEPLOYMENT CHECKLIST

Use this checklist to track deployment progress:

### PRE-DEPLOYMENT (5 min)
- [ ] All files present and verified
- [ ] SSH connectivity tested
- [ ] Have sudo access
- [ ] Network stable
- [ ] Read this entire document

### DURING DEPLOYMENT (30 min)
- [ ] Made scripts executable
- [ ] Started deploy script
- [ ] Watched for errors
- [ ] All steps completed successfully
- [ ] No critical errors occurred

### POST-DEPLOYMENT (10 min)
- [ ] Verified EXISTING design works
- [ ] Verified NEW design works (with ?v=new)
- [ ] Verified API connectivity
- [ ] Started health monitoring
- [ ] Saved deployment summary

### READY FOR TOMORROW (0 min)
- [ ] Opened TESTING_CHECKLIST_TOMORROW.md
- [ ] Understood testing procedure
- [ ] Have rollback procedure if needed
- [ ] Ready for 09:00 AM testing

---

## 🎯 DEPLOYMENT SUMMARY

After successful deployment:

```
DEPLOYMENT RESULT: ✅ SUCCESS

What's Running:
  - EXISTING Design: Stable, tested, default
  - NEW Design: Nature Organic, on test URL
  - Router: Routing traffic intelligently
  - API: Connected and functional

Access URLs Tomorrow:
  - EXISTING (Default): https://prod.stokr.in/trader
  - NEW Design (Test):   https://prod.stokr.in/trader?v=new
  - Admin EXISTING:      https://prod.stokr.in/admin
  - Admin NEW:           https://prod.stokr.in/admin?v=new

Safety Measures:
  ✓ Instant rollback available
  ✓ Full backup created
  ✓ Zero downtime capable
  ✓ No data at risk

Tomorrow's Plan:
  09:00 AM - Manual testing begins
  10:00 AM - Performance checks
  10:45 AM - Decision (continue or rollback)
```

---

## 📞 TROUBLESHOOTING HOTLINE

**Quick Reference Commands:**

```bash
# Check deployment status
curl http://prod.stokr.in/health

# Monitor health continuously
./health-check-prod.sh

# View live logs (NEW design)
ssh root@prod.stokr.in "tail -f /var/log/stokr/new/trader.log"

# View nginx errors
ssh root@prod.stokr.in "tail -f /var/log/nginx/stokr-router-error.log"

# Instant rollback (if critical issue)
sudo ./rollback-prod.sh

# Check if processes running
ssh root@prod.stokr.in "pgrep -af 'python3 -m http.server'"

# Restart services
ssh root@prod.stokr.in "systemctl restart nginx"
```

---

## ✅ YOU ARE READY!

**Summary:**
1. ✅ All files prepared
2. ✅ Deployment scripts created
3. ✅ Rollback procedure available
4. ✅ Monitoring tools ready
5. ✅ Testing checklist prepared

**Next Steps:**
1. Run `sudo ./deploy-to-prod.sh` when ready
2. Wait for completion (20-30 minutes)
3. Verify both designs accessible
4. Prepare for testing tomorrow at 09:00 AM
5. Have rollback script ready as safety net

---

## 🎉 DEPLOYMENT READY!

**Execute deployment now or schedule for specific time:**

```bash
# Execute immediately
sudo ./deploy-to-prod.sh

# Or schedule for specific time (example: 11 PM)
# at 23:00 <<< "cd /path/to/deployment && sudo ./deploy-to-prod.sh"
```

---

**GOOD LUCK! This deployment is SAFE and REVERSIBLE.** ✅

Any issues? Have the rollback script ready and execute `sudo ./rollback-prod.sh`

See you at 09:00 AM for testing! 🚀
