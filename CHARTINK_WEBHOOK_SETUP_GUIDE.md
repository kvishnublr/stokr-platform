# Chartink Premium Webhook Configuration Guide

## 📋 Prerequisites

1. **Chartink Premium Subscription** - Must be active
2. **Server Access** - http://173.249.55.84:8080 must be accessible from Chartink
3. **5 Strategies Configured** - Already done in your database

---

## 🎯 Step-by-Step Configuration

### **Step 1: Login to Chartink Premium**

1. Go to: https://chartink.com
2. Login with your Chartink Premium account
3. Navigate to: **Dashboard → Scanners** or **Alerts**

---

### **Step 2: Create/Configure 5 Scanners**

You need to create **5 separate scanners** in Chartink, one for each strategy:

#### **Scanner 1: VWAP Triple Confirmation**
- **Scanner Name:** `STOKR_VWAP_TRIPLE_LONG`
- **Type:** Intraday (1-minute)
- **Filter Conditions:**
  ```
  Latest Close between 200 and 3000
  Latest Volume > 0
  ```
- **Webhook URL:** `http://173.249.55.84:8080/webhooks/chartink/intraday`
- **Webhook Method:** POST
- **Content-Type:** application/json

#### **Scanner 2: ORB-V Breakout**
- **Scanner Name:** `STOKR_ORB_V_BREAKOUT`
- **Type:** Intraday (1-minute)
- **Filter Conditions:**
  ```
  Latest Close between 200 and 3000
  Latest Volume > 0
  ```
- **Webhook URL:** `http://173.249.55.84:8080/webhooks/chartink/intraday`
- **Webhook Method:** POST
- **Content-Type:** application/json

#### **Scanner 3: Morning Surge Reversal**
- **Scanner Name:** `STOKR_MORNING_SURGE_SHORT`
- **Type:** Intraday (1-minute)
- **Filter Conditions:**
  ```
  Latest Close between 200 and 3000
  Latest Volume > 0
  ```
- **Webhook URL:** `http://173.249.55.84:8080/webhooks/chartink/intraday`
- **Webhook Method:** POST
- **Content-Type:** application/json

#### **Scanner 4: Trade Book Imbalance**
- **Scanner Name:** `STOKR_TRADE_BOOK_IMBALANCE`
- **Type:** Intraday (1-minute)
- **Filter Conditions:**
  ```
  Latest Close between 200 and 3000
  Latest Volume > 0
  ```
- **Webhook URL:** `http://173.249.55.84:8080/webhooks/chartink/intraday`
- **Webhook Method:** POST
- **Content-Type:** application/json

#### **Scanner 5: Pre-Open Buy (SPECIAL)**
- **Scanner Name:** `STOKR_PRE_OPEN_BUY`
- **Type:** Pre-market (runs at 9:09 AM)
- **Filter Conditions:**
  ```
  Latest Close between 200 and 3000
  Latest Volume > 0
  ```
- **Webhook URL:** `http://173.249.55.84:8080/webhooks/chartink/preopen`
- **Webhook Method:** POST
- **Content-Type:** application/json

---

### **Step 3: Configure Webhook Payload**

Chartink needs to send JSON in this format:

```json
{
  "scannerName": "STOKR_VWAP_TRIPLE_LONG",
  "symbol": "RELIANCE",
  "exchange": "NSE",
  "ltp": 2450.50,
  "volume": 150000,
  "buyerQty": 80000,
  "sellerQty": 70000,
  "changePct": 1.5,
  "vwap": 2445.00,
  "rsi14": 65.5,
  "atr14": 25.3,
  "close": 2450.50,
  "open": 2430.00,
  "high": 2455.00,
  "low": 2425.00,
  "prevClose": 2415.00,
  "timestamp": "2026-06-18T09:15:00Z"
}
```

**CRITICAL FIELD:** `scannerName` must match exactly (case-sensitive):
- `STOKR_VWAP_TRIPLE_LONG`
- `STOKR_ORB_V_BREAKOUT`
- `STOKR_MORNING_SURGE_SHORT`
- `STOKR_TRADE_BOOK_IMBALANCE`
- `STOKR_PRE_OPEN_BUY`

---

### **Step 4: Webhook Endpoint Mapping**

| Scanner Type | Endpoint | When It Runs |
|--------------|----------|--------------|
| Pre-Open | `/webhooks/chartink/preopen` | 9:09 AM (before market open) |
| Intraday Scanners | `/webhooks/chartink/intraday` | 9:15 AM - 3:30 PM (every 1 min) |
| Exit Conditions | `/webhooks/chartink/exit` | When exit criteria met |

---

### **Step 5: Testing Configuration**

#### **Test 1: Verify Endpoints Are Reachable**

From your local machine:
```bash
curl -X POST http://173.249.55.84:8080/webhooks/chartink/intraday \
  -H "Content-Type: application/json" \
  -d '{
    "scannerName": "STOKR_VWAP_TRIPLE_LONG",
    "symbol": "RELIANCE",
    "ltp": 2450.50,
    "volume": 100000,
    "changePct": 1.5,
    "timestamp": "2026-06-18T12:00:00Z"
  }'
```

**Expected Response:**
```json
{
  "success": true,
  "signalId": 15,
  "action": "EXECUTED",
  "strategy": "VWAP Triple Confirmation"
}
```

#### **Test 2: Check Server Logs**

```bash
ssh root@173.249.55.84
docker logs stokr-api --tail 50 | grep -i "chartink\|webhook"
```

You should see:
```
Chartink intraday webhook: STOKR_VWAP_TRIPLE_LONG RELIANCE @ 2450.50
Strategy CONFIRMED: RELIANCE BUY | reason=VWAP_TRIPLE_LONG confidence=75.5
```

#### **Test 3: Verify Signal Created**

```bash
ssh root@173.249.55.84
docker exec stokr-postgres psql -U postgres -d stokr_lite \
  -c "SELECT id, symbol, side, status, reason, created_at FROM strategy_signals ORDER BY created_at DESC LIMIT 5;"
```

---

### **Step 6: Activate Scanners**

In Chartink dashboard:
1. Enable all 5 scanners
2. Set scan frequency to **1 minute** for intraday scanners
3. Set pre-open scanner to run at **9:09 AM**
4. Ensure webhook notifications are **ENABLED**
5. Test each scanner manually first

---

## 🔧 Troubleshooting

### **Issue: Webhooks Not Being Received**

**Check 1: Is port 8080 accessible?**
```bash
# From outside the server
curl -I http://173.249.55.84:8080/actuator/health
```

**Check 2: Firewall rules**
```bash
ssh root@173.249.55.84
sudo ufw status
# Port 8080 must be ALLOWED
```

**Check 3: Application is running**
```bash
ssh root@173.249.55.84
docker ps | grep stokr-api
docker logs stokr-api --tail 20
```

### **Issue: "Scanner Not Known" Error**

**Problem:** Scanner name doesn't match expected format

**Solution:** Ensure scanner name is EXACTLY one of:
- `STOKR_VWAP_TRIPLE_LONG`
- `STOKR_ORB_V_BREAKOUT`
- `STOKR_MORNING_SURGE_SHORT`
- `STOKR_TRADE_BOOK_IMBALANCE`
- `STOKR_PRE_OPEN_BUY`

### **Issue: "Strategy Not Confirmed"**

**Problem:** Backend strategy evaluation rejected the signal

**Solution:** Check logs for why:
```bash
docker logs stokr-api 2>&1 | grep "Strategy did not confirm"
```

---

## 📊 Expected Behavior After Configuration

### **During Market Hours (9:15 AM - 3:30 PM):**

1. Chartink scanners run every 1 minute
2. When pattern detected → Webhook sent to your server
3. Backend receives webhook → Evaluates strategy
4. If confirmed → Signal created in database
5. Signal executed → Position opened (LIVE or PAPER mode)

### **Log Flow:**
```
09:15:00 - Chartink intraday webhook: STOKR_VWAP_TRIPLE_LONG RELIANCE @ 2450.50
09:15:01 - Strategy CONFIRMED: RELIANCE BUY | reason=VWAP_TRIPLE_LONG confidence=75.5
09:15:01 - Signal generated: BUY RELIANCE VWAP Triple Confirmation for deployment 1
09:15:02 - Position opened: RELIANCE @ 2450.50 (PAPER)
```

---

## 🎯 Quick Checklist

- [ ] Chartink Premium subscription active
- [ ] 5 scanners created with exact names
- [ ] Webhook URLs configured correctly
- [ ] Scanner filters set (Close 200-3000, Volume > 0)
- [ ] Webhook method: POST
- [ ] Content-Type: application/json
- [ ] Scanners enabled and active
- [ ] Test webhook endpoint manually
- [ ] Verify logs show webhook receipt
- [ ] Monitor signal generation during market hours

---

## 📞 Need Help?

If webhooks still not working after configuration:

1. **Test endpoint manually** (Step 5, Test 1)
2. **Check server logs** for errors
3. **Verify Chartink can reach your server** (port 8080 must be public)
4. **Check if Chartink requires HTTPS** (currently using HTTP)

**Current Setup:**
- Server: http://173.249.55.84:8080
- Status: ✅ Running and ready to receive webhooks
- Database: ✅ Strategies configured
- Code: ✅ Webhook handlers deployed
