# 🔒 PRODUCTION SYSTEM - SECURITY & BLOCKER AUDIT

**System:** stokr.in (Production)  
**Purpose:** Comprehensive security & health check  
**Status:** Audit Required

---

## 🔐 SECURITY CHECKLIST

### **1. Database Security**

```bash
# Check 1: Database access control
psql -U stokr_user -d stokr_platform -c "
SELECT datname, datacl FROM pg_database WHERE datname = 'stokr_platform';
"
✓ Expected: Restricted permissions only to stokr_user

# Check 2: User roles
\du
✓ Expected: Only essential roles (stokr_user, stokr_readonly)
✓ Expected: No default postgres user with access

# Check 3: Password security
\dg
✓ Expected: No empty passwords
✓ Expected: Strong password requirements enforced

# Check 4: Connection security
cat /etc/postgresql/*/main/postgresql.conf | grep -i "ssl\|ssl_cert"
✓ Expected: ssl = on
✓ Expected: SSL certificate configured
```

### **2. API Security**

```bash
# Check 1: JWT validation
curl -s http://localhost:8080/api/protected \ 
  -H "Authorization: Bearer invalid_token"
✓ Expected: 401 Unauthorized (not 200)

# Check 2: CORS configuration
curl -i -X OPTIONS http://localhost:8080/api/trades
✓ Expected: Only allowed origins in Access-Control-Allow-Origin
✓ Expected: No * wildcard (allow all)

# Check 3: Rate limiting
for i in {1..100}; do curl -s http://localhost:8080/api/rates; done
✓ Expected: Request throttled after X attempts
✓ Expected: 429 Too Many Requests returned

# Check 4: Input validation
curl -X POST http://localhost:8080/api/trades \
  -d '{"amount": "'; DROP TABLE trades; --"}'
✓ Expected: Error message, no SQL injection
✓ Expected: Data intact
```

### **3. Authentication & Authorization**

```sql
-- Check 1: Token expiration
SELECT user_id, token_expires_at 
FROM user_sessions 
WHERE token_expires_at < NOW();
✓ Expected: Expired tokens listed and should be cleaned up

-- Check 2: Password hashing
SELECT user_id, password_hash FROM users LIMIT 1;
✓ Expected: Hash (bcrypt), NOT plain text
✓ Expected: Hash starts with $2a$ or $2b$

-- Check 3: User roles
SELECT user_id, role FROM user_roles;
✓ Expected: Users have minimal required roles
✓ Expected: No admin role for regular traders

-- Check 4: Session management
SELECT COUNT(*) FROM user_sessions 
WHERE created_at > NOW() - INTERVAL '30 days' 
AND user_id = (SELECT user_id FROM users LIMIT 1);
✓ Expected: Sessions regularly cleaned up
✓ Expected: No stale sessions
```

### **4. Data Encryption**

```bash
# Check 1: API calls over HTTPS
curl -v https://stokr.in/api/trades 2>&1 | grep "SSL\|TLS"
✓ Expected: TLS 1.2 or higher
✓ Expected: No HTTP (only HTTPS)

# Check 2: Sensitive data in logs
grep -r "password\|token\|secret\|key" /var/log/stokr/ | grep -v "INFO\|DEBUG"
✓ Expected: NO sensitive data in logs
✓ Expected: Passwords never logged

# Check 3: Database encryption
SELECT datname, datacl FROM pg_database;
✓ Expected: Database at rest encrypted (LUKS or similar)

# Check 4: Credentials in code
grep -r "password\|api_key\|secret" /app/src/ --include="*.java" --include="*.yml"
✓ Expected: NO hardcoded credentials
✓ Expected: All from environment variables
```

### **5. File & Directory Permissions**

```bash
# Check 1: Sensitive files
ls -la /app/config/
✓ Expected: application.yml NOT readable by all (600)
✓ Expected: .env NOT readable by all (600)
✓ Expected: private keys NOT world-readable

# Check 2: Application directory
ls -la /app/
✓ Expected: Owned by app user (not root)
✓ Expected: Not world-writable

# Check 3: Log directory
ls -la /var/log/stokr/
✓ Expected: Not world-readable
✓ Expected: Sensitive logs protected

# Check 4: Upload directory
ls -la /app/uploads/
✓ Expected: Not executable
✓ Expected: Not world-writable
```

---

## 🚨 BLOCKER CHECKLIST

### **1. System Resources**

```bash
# Check 1: Disk space
df -h /
✓ Expected: > 20% free space (not < 10%)
✓ Alert: If < 10%, system may crash

# Check 2: Memory
free -m
✓ Expected: Available memory > 500 MB
✓ Alert: If < 100 MB, OutOfMemory risk

# Check 3: CPU
top -bn1 | head -15
✓ Expected: CPU usage < 80% sustained
✓ Alert: If > 90%, performance degraded

# Check 4: Open file descriptors
lsof | wc -l
✓ Expected: < 50% of system limit
✓ Alert: If > 80%, connection issues likely
```

### **2. Application Health**

```bash
# Check 1: Application running
systemctl status stokr-app
✓ Expected: active (running)
✓ Alert: If inactive, app is down

# Check 2: Port listening
netstat -tlnp | grep 8080
✓ Expected: Java process listening on 8080
✓ Alert: If not listening, app crashed

# Check 3: Recent crashes
journalctl -u stokr-app -n 50 | grep -i "error\|crash\|exception"
✓ Expected: No recent errors
✓ Alert: If recent errors, investigate

# Check 4: Response time
curl -w "%{time_total}\n" -o /dev/null -s https://stokr.in/
✓ Expected: < 1 second
✓ Alert: If > 3 seconds, performance issue
```

### **3. Database Health**

```sql
-- Check 1: Connection count
SELECT datname, count(*) FROM pg_stat_activity GROUP BY datname;
✓ Expected: < max_connections / 2
✓ Alert: If > 80%, connection pool exhausted

-- Check 2: Query performance
SELECT query, calls, mean_time 
FROM pg_stat_statements 
ORDER BY mean_time DESC LIMIT 5;
✓ Expected: No query > 1000ms
✓ Alert: If > 5000ms, slow query found

-- Check 3: Table sizes
SELECT schemaname, tablename, pg_size_pretty(pg_total_relation_size(schemaname||'.'||tablename)) 
FROM pg_tables 
ORDER BY pg_total_relation_size(schemaname||'.'||tablename) DESC 
LIMIT 10;
✓ Expected: No table growing unexpectedly
✓ Alert: If > 50GB, cleanup needed

-- Check 4: Replication lag (if replicated)
SELECT slot_name, restart_lsn FROM pg_replication_slots;
✓ Expected: No slots stuck
✓ Alert: If lag > 1MB, replication issue
```

### **4. API & Service Integration**

```bash
# Check 1: Zerodha API connection
curl -s http://localhost:8080/api/health/zerodha | jq '.'
✓ Expected: status = "CONNECTED"
✓ Alert: If DISCONNECTED, live trading at risk

# Check 2: Market data feed
curl -s http://localhost:8080/api/market/status | jq '.'
✓ Expected: feed_status = "LIVE"
✓ Expected: last_update < 5 seconds ago
✓ Alert: If stale, quotes delayed

# Check 3: Email notifications
curl -X POST http://localhost:8080/api/test/email \
  -d '{"to":"admin@stokr.in","subject":"Test"}'
✓ Expected: Email sent successfully
✓ Alert: If fails, alerts won't send

# Check 4: Webhook endpoints
curl -s http://localhost:8080/api/webhooks/status | jq '.'
✓ Expected: All registered webhooks responding
✓ Alert: If any failing, events lost
```

### **5. Backup & Recovery**

```bash
# Check 1: Database backups
ls -lah /backups/database/
✓ Expected: Daily backups present
✓ Expected: Most recent < 24 hours old
✓ Alert: If no recent backup, disaster risk

# Check 2: Backup integrity
pg_verify_backup -D /backups/database/latest/
✓ Expected: Backup is valid and recoverable
✓ Alert: If corrupted, backups useless

# Check 3: Application logs backup
ls -lah /backups/logs/
✓ Expected: Recent log backups
✓ Expected: Rotation working

# Check 4: Recovery test
date; echo "Recovery tested at $(date)" >> /backups/recovery.log
✓ Expected: Can write to backup location
✓ Expected: Recovery procedure documented
```

### **6. Monitoring & Alerting**

```bash
# Check 1: Monitoring agent running
systemctl status prometheus-node-exporter
✓ Expected: active (running)
✓ Alert: If stopped, no metrics collected

# Check 2: Alert rules configured
curl -s http://localhost:9090/api/v1/rules | jq '.data.groups | length'
✓ Expected: > 10 alert rules
✓ Alert: If < 5, insufficient monitoring

# Check 3: Recent alerts
curl -s http://localhost:9090/api/v1/alerts | jq '.data | length'
✓ Expected: No firing alerts (or only expected ones)
✓ Alert: If firing, investigate

# Check 4: Log aggregation
curl -s http://localhost:5000/api/loki/query_range | jq '.'
✓ Expected: Logs being collected
✓ Alert: If no logs, collection broken
```

---

## 📋 PRODUCTION SECURITY AUDIT CHECKLIST

Run these commands and report findings:

```
SECURITY:
☐ Database access restricted (non-default users only)
☐ SSL/TLS enabled on all connections
☐ JWT tokens validated on protected endpoints
☐ CORS properly configured (no *)
☐ Rate limiting active
☐ Input validation (no SQL injection)
☐ Passwords hashed (bcrypt)
☐ No hardcoded credentials in code
☐ Sensitive files have restricted permissions
☐ Logs don't contain sensitive data

BLOCKERS:
☐ Application running (systemctl status)
☐ Port 8080 listening (netstat)
☐ Disk space > 20% available
☐ Memory available > 500 MB
☐ CPU usage < 80%
☐ Database connections healthy
☐ No slow queries (> 5000ms)
☐ Zerodha API connected
☐ Market data feed active (< 5s lag)
☐ Recent backups present (< 24h)
☐ Monitoring/alerts active
☐ Email service working
☐ No recent application crashes

COMPLIANCE:
☐ HTTPS enforced (no HTTP)
☐ Session management working
☐ User roles minimal (principle of least privilege)
☐ Audit logging enabled
☐ Data encryption at rest
☐ Data encryption in transit
```

---

## 🔴 CRITICAL ISSUES (STOP if any found)

```
BLOCKER - Production Down:
❌ Application not running
❌ Port not listening
❌ Database not accessible
❌ Disk space < 5%

BLOCKER - Live Trading at Risk:
❌ Zerodha API disconnected
❌ Market data > 60 seconds stale
❌ Backup missing/corrupted
❌ Position size limits not enforced

BLOCKER - Security Breach:
❌ Hardcoded credentials in code
❌ Sensitive data in logs
❌ SQL injection vulnerabilities
❌ CORS allowing all origins (*)
❌ Default database password in use
```

---

## 📞 ACTION ITEMS

### **If issues found:**
1. Document each issue with exact command output
2. Categorize: CRITICAL, HIGH, MEDIUM, LOW
3. Create fix ticket for each issue
4. Rerun audit after fixes

### **Regular audit schedule:**
- Daily: Application health (5 min check)
- Weekly: Security audit (30 min check)
- Monthly: Comprehensive audit (2 hour check)
- Quarterly: Full penetration test (external)

---

## ✅ APPROVAL

Once all checks pass:
```
PRODUCTION SECURITY:     ✓ APPROVED
PRODUCTION BLOCKERS:     ✓ CLEAR
LIVE TRADING READY:      ✓ YES
SAFE TO DEPLOY FEATURES: ✓ YES
```

---

**Run these audits now and report findings.**
