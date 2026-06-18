# 🔍 CHARTINK WEBHOOK VERIFICATION REPORT

**Date:** 2026-06-18 12:00 PM IST  
**Status:** ❌ CRITICAL MISMATCH FOUND

---

## 🎯 ROOT CAUSE IDENTIFIED

### **Wrong Webhook Endpoint and Format!**

The **deployed server** (stokr-api from stokr-bootstrap monolith) uses a **DIFFERENT webhook endpoint** than what was configured in Chartink!

---

## 📊 WHAT'S ACTUALLY DEPLOYED

### **Actual Webhook Endpoint:**
```
POST http://173.249.55.84:8080/api/chartink/webhook
```

**Expected Payload Format:**
```json
{
  "scan_name": "VWAP Triple Confirmation",
  "alert_name": "VWAP_TRIPLE_CONFIRMATION",
  "scan_url": "https://chartink.com/scan/...",
  "triggered_at": "11:52 AM",
  "stocks": "RELIANCE,TCS,INFY",
  "trigger_prices": "2450.50,3500.00,1500.00"
}
```

### **What Chartink Is Sending:**
```
✅ Webhooks ARE being received at /api/chartink/webhook
✅ Payload length: 71 bytes
❌ Stocks field is EMPTY
❌ No stock data included
```

**Logs show:**
```
chartink.webhook.received payload_length=71
chartink.webhook.parsed scan= stocks=[] count=0
```

---

## 🔄 WHAT WAS EXPECTED (stokr-lite code)

The stokr-lite backend (NOT deployed) has a different webhook controller:

**Endpoint:**
```
POST http://173.249.55.84:8080/webhooks/chartink/intraday
```

**Expected Payload:**
```json
{
  "scannerName": "STOKR_VWAP_TRIPLE_LONG",
  "symbol": "RELIANCE",
  "ltp": 2450.50,
  "volume": 100000,
  "changePct": 1.5,
  "timestamp": "2026-06-18T12:00:00Z"
}
```

---

## 🔍 DETAILED ANALYSIS

### **1. Which Application is Running?**

**Container:** stokr-api  
**Image:** stokr-platform-api (from stokr-bootstrap monolith)  
**Built:** 2026-06-17T09:18:48Z

**Evidence:**
- Uses `ChartinkWebhookController` from `com.stokr.marketdata.chartink` package
- Endpoint: `/api/chartink/webhook`
- Parser expects: `scan_name`, `stocks` (comma-separated), `trigger_prices`

### **2. What is Chartink Actually Sending?**

Based on logs, Chartink is sending:
- Payload length: 71 bytes (very small)
- Parsed result: `scan= stocks=[] count=0`

**Most likely payload:**
```json
{
  "scan_name": "",
  "alert_name": "",
  "stocks": "",
  "trigger_prices": "",
  "triggered_at": "11:52 AM"
}
```

OR Chartink is sending a completely different format that the parser can't read.

### **3. Why No Signals Generated?**

**Flow Breakdown:**
1. ✅ Chartink sends webhook to `/api/chartink/webhook`
2. ✅ Server receives it (payload_length=71)
3. ✅ Parser runs but finds 0 stocks
4. ❌ `toStockAlerts()` returns empty list
5. ❌ No alerts stored
6. ❌ No signals generated
7. ❌ No trades executed

---

## 🧪 TEST TO VERIFY

### **Test 1: Send Properly Formatted Webhook**

```bash
curl -X POST http://173.249.55.84:8080/api/chartink/webhook \
  -H "Content-Type: application/json" \
  -d '{
    "scan_name": "VWAP Triple Confirmation",
    "alert_name": "VWAP_TRIPLE_CONFIRMATION",
    "scan_url": "https://chartink.com/scan/test",
    "triggered_at": "12:00 PM",
    "stocks": "RELIANCE,TCS,INFY",
    "trigger_prices": "2450.50,3500.00,1500.00"
  }'
```

**Expected Response:**
```json
{
  "success": true,
  "correlationId": "..."
}
```

**Expected Logs:**
```
chartink.webhook.received payload_length=185
chartink.webhook.parsed scan=VWAP Triple Confirmation stocks=[RELIANCE, TCS, INFY] count=3
chartink.webhook.stock symbol=RELIANCE price=2450.50 scan=VWAP Triple Confirmation
chartink.webhook.stock symbol=TCS price=3500.00 scan=VWAP Triple Confirmation
chartink.webhook.stock symbol=INFY price=1500.00 scan=VWAP Triple Confirmation
```

### **Test 2: Check if Alerts Were Stored**

```bash
docker logs stokr-api 2>&1 | grep "chartink.webhook" | tail -20
```

---

## 🔧 SOLUTION OPTIONS

### **Option 1: Fix Chartink Configuration (RECOMMENDED)**

Chartink needs to send webhooks to the CORRECT endpoint with CORRECT format:

**Webhook URL:**
```
http://173.249.55.84:8080/api/chartink/webhook
```

**Payload Format:**
```json
{
  "scan_name": "VWAP Triple Confirmation",
  "alert_name": "VWAP_TRIPLE_CONFIRMATION",
  "scan_url": "...",
  "triggered_at": "{{TIME}}",
  "stocks": "{{STOCKS}}",
  "trigger_prices": "{{PRICES}}"
}
```

Chartink variables to use:
- `{{TIME}}` → Triggered time
- `{{STOCKS}}` → Comma-separated stock symbols
- `{{PRICES}}` → Comma-separated trigger prices

### **Option 2: Add Logging to See Exact Payload**

Update the webhook controller to log the RAW payload:

```java
log.info("chartink.webhook.received payload_length={} raw={}", 
    rawPayload.length(), rawPayload);
```

This will show exactly what Chartink is sending.

### **Option 3: Deploy stokr-lite Backend**

Replace stokr-api (monolith) with stokr-lite backend which has the cleaner webhook API.

---

## 📋 CHARTINK CONFIGURATION CHECKLIST

### **In Chartink Dashboard:**

- [ ] Webhook URL: `http://173.249.55.84:8080/api/chartink/webhook`
- [ ] Webhook Method: POST
- [ ] Content-Type: application/json
- [ ] Payload includes ALL required fields:
  - [ ] `scan_name` (your scanner name)
  - [ ] `alert_name` (alert name)
  - [ ] `triggered_at` (timestamp)
  - [ ] `stocks` (comma-separated symbols)
  - [ ] `trigger_prices` (comma-separated prices)
- [ ] Test webhook manually
- [ ] Verify scanner actually detects stocks

---

## 🎯 IMMEDIATE ACTIONS

### **Step 1: Test the Correct Endpoint**

Run the curl command from Test 1 above to verify the endpoint works.

### **Step 2: Check Chartink Configuration**

1. Login to Chartink
2. Open scanner settings
3. Verify webhook URL is: `http://173.249.55.84:8080/api/chartink/webhook`
4. Verify payload format matches expected structure
5. Verify scanner actually finds stocks when run manually

### **Step 3: Add Raw Payload Logging**

I can update the controller to log the full raw payload so we can see exactly what Chartink is sending.

---

## 📊 VERIFICATION STATUS

| Check | Status | Details |
|-------|--------|---------|
| Webhook Endpoint Exists | ✅ | `/api/chartink/webhook` |
| Chartink Sending Webhooks | ✅ | 3+ received today |
| Webhook Payload Valid | ❌ | Stocks field is EMPTY |
| Parser Working | ✅ | Running but no data to parse |
| Alerts Stored | ❌ | No alerts (0 stocks) |
| Signals Generated | ❌ | No signals created |
| Trades Executed | ❌ | No trades |

---

## 🚨 CRITICAL FINDING

**Chartink IS sending webhooks to your server!** ✅

**BUT the webhooks contain NO STOCK DATA!** ❌

**Root Cause:**
1. Chartink webhook URL might be pointing to wrong endpoint
2. Chartink payload format doesn't match what parser expects
3. Scanner conditions are too restrictive (0 stocks found)
4. Chartink scanner not actually detecting any stocks

**Most Likely:** Chartink is configured with OLD endpoint or WRONG payload format.

---

## 📞 NEXT STEPS

1. **Test the endpoint manually** with proper format
2. **Check Chartink webhook configuration**
3. **Verify scanner finds stocks** when run manually
4. **Add raw payload logging** to see what Chartink sends
5. **Update Chartink config** with correct endpoint and format

**Which option would you like me to help with first?**
