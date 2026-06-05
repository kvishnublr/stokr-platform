# 🏗️ Infrastructure Health Center - Complete Guide

**Status:** ✅ **LIVE & OPERATIONAL**

---

## 📊 DASHBOARD OVERVIEW

Your infrastructure health dashboard provides **REAL-TIME VISUAL MONITORING** with:

### 🎨 What You'll See

```
┌─────────────────────────────────────────────────────────┐
│  Infrastructure Health Center - System Components       │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  🟢 Market Feed      🟡 OMS           🟢 Redis         │
│     CLOSED (⚠️)        READY (✓)        CONNECTED      │
│     Status Alert     Load: 37.80%      Latency: 15ms  │
│                                                         │
│  🟢 RabbitMQ        🟢 PostgreSQL     🟢 Signal Eng    │
│     CONNECTED        CONNECTED          RUNNING        │
│     Latency: 12ms   Response: 8ms     11 instances    │
│                                                         │
│  🟢 Broker Rail     🟡 Kill Switch                     │
│     CONNECTED        ARMED (Safety)                    │
│     Latency: 45ms   Execution Hot                      │
│                                                         │
└─────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────┐
│  Key Metrics - Real-time Gauges                         │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  CPU Usage: 34%          Memory: 62%  (⚠️ Monitor)    │
│  ████████░░░░░░░░       ██████████████░░░░░░░        │
│                                                         │
│  Disk I/O: 28%           Network: 18%                  │
│  ██████░░░░░░░░░░░░     ███░░░░░░░░░░░░░░░░░░       │
│                                                         │
└─────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────┐
│  ⚠️  ALERTS & ISSUES                                    │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  🟡 WARNING: Market Feed CLOSED                        │
│     Trading halted - market hours violation            │
│     Time: 13:35                                        │
│                                                         │
│  🟡 INFO: Kill Switch ARMED                           │
│     Emergency stop activated - execution plane hot    │
│     Time: 13:30                                        │
│                                                         │
└─────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────┐
│  Event Timeline - What Happened When                    │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  13:35 ● Market Feed closed - automatic halt           │
│  13:30 ● Kill Switch activated - safety engaged       │
│  13:20 ● OMS load at 37.80% - monitoring increased    │
│  13:10 ● Redis connected - 15ms latency               │
│  13:05 ● RabbitMQ processing 45,234 messages          │
│  13:00 ● All systems nominal - connected              │
│  12:55 ● Signal Engine fired 234 signals              │
│  12:50 ● Broker Rail optimized to 45ms                │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

---

## 🌐 ACCESS THE DASHBOARD

### **In Browser:**
```
http://localhost:8080/infrastructure-health
```

### **Features:**

| Feature | Description |
|---------|-------------|
| 🔴 Color Coding | Green=Healthy, Yellow=Warning, Red=Critical |
| 🔄 Auto Refresh | Updates every 5 seconds automatically |
| 🖱️ Manual Refresh | Click refresh button to update now |
| 📊 Live Gauges | CPU, Memory, Disk, Network in real-time |
| ⏱️ Timeline | See WHEN each event occurred |
| 🚨 Issue Alerts | Critical issues highlighted at top |
| 💫 Animations | Pulsing indicators show live status |

---

## 📡 7 REST APIs - All Accessible

### **1. Health Snapshot (30-second overview)**
```bash
curl http://localhost:8080/api/infrastructure/health-snapshot
```

**Response:**
```json
{
  "timestamp": "2026-06-05T13:35:00Z",
  "overall_status": "HEALTHY",
  "critical_issues": 0,
  "warnings": 1,
  "components": {
    "market_feed": { "status": "CLOSED", "health": "warning" },
    "oms": { "status": "READY", "health": "healthy" },
    "redis": { "status": "CONNECTED", "health": "healthy" },
    "rabbitmq": { "status": "CONNECTED", "health": "healthy" },
    "postgresql": { "status": "CONNECTED", "health": "healthy" },
    "signal_engine": { "status": "RUNNING", "health": "healthy" },
    "broker_rail": { "status": "CONNECTED", "health": "healthy" },
    "kill_switch": { "status": "ARMED", "health": "warning" }
  },
  "metrics": {
    "cpu_usage": 34,
    "memory_usage": 62,
    "disk_io": 28,
    "network_usage": 18
  }
}
```

### **2. Component Details**
```bash
curl http://localhost:8080/api/infrastructure/components
```

**Shows:** Each component's status, latency, uptime, and details

### **3. Issues**
```bash
curl http://localhost:8080/api/infrastructure/issues
```

**Shows:** All current issues with severity levels

### **4. Timeline**
```bash
curl http://localhost:8080/api/infrastructure/timeline
```

**Shows:** Event history - what happened and when

### **5. Detailed Metrics**
```bash
curl http://localhost:8080/api/infrastructure/metrics/detailed
```

**Shows:** System, performance, database, and cache metrics

### **6. Component Status (via Admin Diagnostics)**
```bash
curl http://localhost:8080/api/admin/diagnostics/component-status
```

### **7. Root Cause Analysis**
```bash
curl http://localhost:8080/api/admin/diagnostics/root-cause
```

---

## 🎯 STATUS INDICATORS GUIDE

### **Color Meanings:**

| Color | Meaning | Example |
|-------|---------|---------|
| 🟢 GREEN | HEALTHY | Redis connected, 15ms latency |
| 🟡 YELLOW | WARNING | Market feed closed (expected), Kill switch armed |
| 🔴 RED | CRITICAL | Database down, Broker disconnected, High error rate |

### **Component Status:**

```
Market Feed:        CLOSED          (Normal during market hours)
OMS:                READY           (Load: 37.80% - monitor)
Redis:              CONNECTED       (Latency: 15ms - healthy)
RabbitMQ:           CONNECTED       (Processing normally)
PostgreSQL:         CONNECTED       (Response: 8ms - fast)
Signal Engine:      RUNNING         (11 instances active)
Broker Rail:        CONNECTED       (Latency: 45ms - acceptable)
Kill Switch:        ARMED           (Safety mechanism - expected)
```

---

## 📈 Key Metrics Explained

### **System Metrics:**
- **CPU Usage:** 34% - Normal, plenty of headroom
- **Memory Usage:** 62% - Good, monitor if >80%
- **Disk I/O:** 28% - Low, no bottlenecks
- **Network:** 18% - Low, capacity available

### **Performance Metrics:**
- **Request Latency Avg:** 245ms - Healthy
- **Request Latency P99:** 890ms - Within limits
- **Error Rate:** 0.02% - Excellent
- **Success Rate:** 99.98% - High reliability

### **Database Metrics:**
- **Connection Pool:** 45% used (9/20 connections)
- **Query Time:** 8ms average - Very fast
- **Active Connections:** 9 - Normal

### **Cache Metrics:**
- **Hit Rate:** 87.5% - Excellent cache performance
- **Miss Rate:** 12.5% - Normal
- **Memory Used:** 512MB - Within limits

---

## 🚨 Alert Examples

### **Warning: Market Feed Closed**
```
Status: CLOSED
Time: 13:35
Reason: Market hours violation (after 3:30 PM)
Action: Automatic trading halt triggered
Fix: Market will reopen at 9:15 AM next trading day
```

### **Info: Kill Switch Armed**
```
Status: ARMED
Time: 13:30
Reason: Safety mechanism activated
Indication: Execution plane is hot
Action: All trading halted until disarmed
```

### **Info: High OMS Load**
```
Status: READY (37.80% load)
Time: 13:20
Reason: Elevated order processing
Action: Monitoring increased (threshold: 70%)
```

---

## 🎮 Interactive Controls

| Button | Function |
|--------|----------|
| 🔄 Refresh Now | Immediately fetch latest data |
| ⚙️ Auto Refresh | Toggle 5-second auto-refresh |
| 📊 Hover Components | See detailed information |
| 👆 Click Sections | Expand/collapse details |

---

## 🔧 Configuration

### **Auto-Refresh Interval:**
- **Default:** 5 seconds (configurable)
- **Manual:** Click "Refresh Now" button

### **Theme:**
- **Dark Mode:** Professional dark blue theme (better for ops teams)
- **Responsive:** Works on desktop, tablet, and mobile

### **Data Sources:**
All data comes from real-time API endpoints:
```
/api/infrastructure/*
/api/admin/diagnostics/*
```

---

## 📋 Typical Workflow

### **1. Morning Check**
- Open `/infrastructure-health`
- Scan components for any red indicators
- Review timeline for overnight events
- Check metrics for anomalies

### **2. During Trading**
- Watch auto-refresh every 5 seconds
- Look for yellow/red status changes
- Check alert panel for new issues
- Monitor key metrics (CPU, Memory)

### **3. Issue Response**
- See issue in alert panel (top priority)
- Click issue to get details
- Check timeline to understand sequence
- Review metrics to identify root cause
- Take corrective action

### **4. Post-Market**
- Archive timeline for audit
- Check for any unresolved issues
- Verify all systems back to HEALTHY
- Document any incidents

---

## ✅ Status Reference

### **All Green (HEALTHY)**
```
✅ All components connected
✅ No critical issues
✅ Metrics within normal ranges
✅ System operational
✅ Trading active
```

### **Yellow Alert (WARNING)**
```
⚠️ Market Feed CLOSED (expected - outside trading hours)
⚠️ Kill Switch ARMED (safety engaged)
⚠️ Memory approaching limit (>70%)
⚠️ Latency increasing
→ Monitor and prepare for action
```

### **Red Alert (CRITICAL)**
```
🔴 Component DISCONNECTED
🔴 Error Rate >5%
🔴 CPU >90%
🔴 Database down
→ Immediate action required
```

---

## 🎉 Summary

Your Infrastructure Health Dashboard provides:

✅ **Real-time Component Monitoring** - See status of all 8 core systems  
✅ **Graphical Status Indicators** - Color-coded (green/yellow/red)  
✅ **Live Metric Gauges** - CPU, Memory, Disk, Network  
✅ **Issue Detection** - Automatic critical issue alerts  
✅ **Event Timeline** - See exactly when each issue occurred  
✅ **Professional Visualization** - Dark theme, animations, responsive  
✅ **7 REST APIs** - Full data access for integration  
✅ **Auto-Refresh** - Updates every 5 seconds  

---

**Access Now:** `http://localhost:8080/infrastructure-health`

**Latest Commit:** Ready for deployment  
**Status:** ✅ LIVE & OPERATIONAL
