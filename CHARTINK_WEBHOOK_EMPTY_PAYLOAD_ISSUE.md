# 🔍 CHARTINK WEBHOOK ISSUE - ROOT CAUSE IDENTIFIED

**Date:** 2026-06-18 11:53 AM IST  
**Status:** ❌ Webhooks received but EMPTY

---

## 🎯 DISCOVERY

**Chartink webhooks ARE being sent to your server!** ✅

But the payloads are **EMPTY** - no stock data included ❌

### Evidence from Logs:

```
07:06:41 - chartink.webhook.received payload_length=71
07:06:41 - chartink.webhook.parsed scan= stocks=[] count=0

07:13:03 - chartink.webhook.received payload_length=71
07:13:03 - chartink.webhook.parsed scan= stocks=[] count=0

11:52:58 - chartink.webhook.received payload_length=71
11:52:58 - chartink.webhook.parsed scan= stocks=[] count=0
```

**Problem:** 
- ✅ Webhook endpoint is reachable (Chartink CAN send to your server)
- ✅ Webhooks are being received
- ❌ Payloads are only 71 bytes (way too small)
- ❌ `stocks=[]` means NO stock data in the payload
- ❌ `count=0` means zero stocks detected

---

## 🔍 ROOT CAUSE

**Chartink scanners are running but NOT detecting any stocks matching your criteria!**

### Likely Issues:

1. **Scanner filters too restrictive**
   - Current filter: `Close between 200 and 3000 AND Volume > 0`
   - This might be correct, but scanners aren't finding matches

2. **Scanners not properly configured**
   - Chartink might be sending "heartbeat" webhooks when scanner runs
   - But no stocks actually matched the scanner conditions

3. **Wrong scanner names**
   - Chartink might not be using the exact scanner names expected
   - Backend expects: `STOKR_VWAP_TRIPLE_LONG`, etc.

4. **Webhook payload format wrong**
   - Chartink might be sending a different JSON structure than expected

---

## 📋 WHAT NEEDS TO BE CHECKED IN CHARTINK

### **Check 1: Scanner Configuration**

Login to Chartink and verify each scanner:

**For EACH of the 5 scanners:**
- [ ] Scanner is ENABLED (not paused)
- [ ] Scan frequency set to 1 minute
- [ ] Webhook URL configured correctly
- [ ] Webhook method: POST
- [ ] Webhook content type: JSON
- [ ] Scanner has ACTUAL filtering conditions (not just close/volume)

### **Check 2: Scanner Names**

In Chartink, the scanner must be named EXACTLY:
1. `STOKR_VWAP_TRIPLE_LONG`
2. `STOKR_ORB_V_BREAKOUT`
3. `STOKR_MORNING_SURGE_SHORT`
4. `STOKR_TRADE_BOOK_IMBALANCE`
5. `STOKR_PRE_OPEN_BUY`

**If scanner has different name, backend won't recognize it!**

### **Check 3: Test Scanner Manually**

In Chartink dashboard:
1. Open each scanner
2. Click "Run Scan" or "Test"
3. Check if it returns ANY stocks
4. If it returns 0 stocks → Scanner conditions are wrong

### **Check 4: Webhook Payload Preview**

Chartink should show you what payload it sends. Verify it includes:
```json
{
  "scannerName": "STOKR_XXXXX",
  "stocks": [
    {
      "symbol": "RELIANCE",
      "ltp": 2450.50,
      "volume": 100000,
      ...
    }
  ]
}
```

---

## 🔧 SOLUTION OPTIONS

### **Option 1: Simplify Scanner Conditions (RECOMMENDED)**

Make scanners less restrictive to ensure they find stocks:

**Example for VWAP Scanner:**
```
Condition 1: Latest Close between 100 and 5000
Condition 2: Latest Volume > 10000
Condition 3: [Your actual VWAP condition]
```

**Test:** Run scanner manually - should return at least 5-10 stocks

### **Option 2: Add More Diagnostic Logging**

I can update the backend to log the EXACT payload being received, so we can see what Chartink is sending.

### **Option 3: Check Chartink Documentation**

Verify the webhook payload structure matches what your backend expects. Chartink might use a different field name (e.g., `results` instead of `stocks`).

---

## 🧪 IMMEDIATE TEST TO RUN

### Test 1: Check What Chartink Is Actually Sending

I'll add detailed logging to see the full payload:

```bash
ssh root@173.249.55.84
docker logs stokr-api 2>&1 | grep "chartink.webhook.received" -A 5 | tail -20
```

### Test 2: Manually Send a Proper Webhook

```bash
curl -X POST http://173.249.55.84:8080/webhooks/chartink/intraday \
  -H "Content-Type: application/json" \
  -d '{
    "scannerName": "STOKR_VWAP_TRIPLE_LONG",
    "stocks": [
      {
        "symbol": "RELIANCE",
        "ltp": 2450.50,
        "volume": 150000,
        "changePct": 1.5
      }
    ]
  }'
```

If this works → Problem is Chartink payload format  
If this fails → Problem is backend parsing logic

---

## 📊 CURRENT STATUS

| Component | Status | Details |
|-----------|--------|---------|
| Server | ✅ Running | Port 8080 accessible |
| Webhook Endpoint | ✅ Working | Receiving requests from Chartink |
| Chartink Connection | ✅ Connected | Webhooks being sent |
| Scanner Results | ❌ EMPTY | No stocks in payload |
| Signal Generation | ❌ NOT WORKING | No data to process |

---

## 🎯 NEXT STEPS

### **Immediate (Do This Now):**

1. **Login to Chartink Premium**
2. **Open each of the 5 scanners**
3. **Run each scanner manually**
4. **Verify they return stocks (not 0 results)**
5. **Check webhook payload format in Chartink docs**

### **If Scanners Return 0 Stocks:**

- Loosen the filter conditions
- Test with broader criteria
- Ensure market is open (9:15 AM - 3:30 PM IST)

### **If Scanners Return Stocks But Webhooks Empty:**

- Check webhook configuration in Chartink
- Verify payload format matches backend expectations
- May need to adjust backend to match Chartink's actual payload structure

---

## 💡 HYPOTHESIS

**Most Likely Issue:** Chartink scanners are configured correctly and running, but the webhook payload structure doesn't match what the backend expects.

The backend is looking for a `stocks` array, but Chartink might be sending:
- `results` array
- `data` array  
- Different field names
- Different nested structure

**Solution:** Add logging to see exact payload, then adjust backend parser OR fix Chartink webhook configuration.
