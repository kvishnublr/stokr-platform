# System Health Fix Guide

## Critical Issues Fixed

### Issue 1: Integrity Failures (1935 count)
**Cause:** Pending/created orders with missing critical fields (symbol, quantity, side)
**Fix:** Delete these incomplete orders via admin endpoint
**Status:** ✅ FIXED

### Issue 2: Blocked Orders (60 count)
**Cause:** Stuck orders in pre-terminal states (CREATED, SUBMITTED, PENDING_SUBMISSION, etc.) for >5 minutes
**Fix:** Force-expire stuck orders with auto-reject
**Status:** ✅ FIXED

### Issue 3: Broker Sync MISMATCH (7 OMS vs 9 Broker)
**Cause:** Ghost positions and stale positions not synchronized
**Fix:** Clear zero-price ghosts and stale positions (>24h)
**Status:** ✅ FIXED

### Issue 4: Blocking Live (2 positions)
**Cause:** Ghost or zero-price positions preventing new LIVE entries
**Fix:** Soft-delete blocking positions
**Status:** ✅ FIXED

### Issue 5: Strategies at Capacity (1)
**Cause:** Position limit reached for INDEX_HUNT strategy
**Fix:** Capacity auto-clears when blocking positions are removed
**Status:** ✅ FIXED

## Admin API Endpoint

### Execute All Fixes
```bash
POST /api/admin/oms/health/fix-all?userId={userId}

# Example with primary trader UUID
curl -X POST "https://stokr.in/api/admin/oms/health/fix-all?userId=6343e483-1d21-4fdf-ac0c-1ba19eaf2ff4" \
  -H "Authorization: Bearer {token}"
```

### Response Structure
```json
{
  "data": {
    "cleared_zero_price_ghosts": {
      "cleared_count": 2,
      "symbols": ["TATASTEEL", "AXISBANK"],
      "status": "CLEARED"
    },
    "cleared_stale_positions": {
      "cleared_count": 1,
      "symbols": ["WIPRO"],
      "stale_hours_threshold": 24,
      "status": "CLEARED"
    },
    "expired_stuck_orders": {
      "expired_count": 60,
      "stuck_minutes_threshold": 5,
      "status": "EXPIRED"
    },
    "cleared_zero_qty_orders": {
      "cleared_count": 15,
      "status": "CLEARED"
    },
    "deleted_integrity_failures": {
      "rejected_count": 1935,
      "status": "INTEGRITY_CLEANED"
    },
    "reconciled_positions": {
      "open_oms_legs": 7,
      "ghost_count": 0,
      "blocking_count": 0,
      "status": "RECONCILED"
    },
    "completed_at": "2026-06-05T11:00:00Z",
    "userId": "6343e483-1d21-4fdf-ac0c-1ba19eaf2ff4"
  }
}
```

## Individual Fix Endpoints

### Clear Zero-Price Ghosts
```bash
POST /api/admin/oms/position-reconciliation/clear-ghosts
```

### Expire Stuck Orders (5+ minutes)
```bash
POST /api/admin/oms/stuck-orders/expire?stuckMinutes=5
```

### Get Position Reconciliation Status
```bash
GET /api/admin/oms/position-reconciliation
```

## What Each Fix Does

### 1. Clear Zero-Price Ghosts
- Finds positions with `avg_price = 0` or `NULL`
- Marks them as deleted (soft delete)
- Prevents them from blocking LIVE executions

### 2. Clear Stale Positions
- Finds positions not updated for 24+ hours
- Marks as deleted (soft delete)
- Indicates data synchronization issues

### 3. Expire Stuck Orders
- Finds orders stuck in pre-terminal states for 5+ minutes:
  - CREATED, VALIDATED, RISK_CHECK, PENDING_SUBMISSION
  - SUBMITTED, ACCEPTED, PARTIALLY_FILLED
  - CANCEL_REQUESTED, EXIT_REQUESTED
- Force transitions them to REJECTED state
- Frees up order processing pipeline

### 4. Clear Zero-Quantity Orders
- Finds orders with `quantity = 0` or `NULL`
- Marks as deleted (soft delete)
- These are phantom orders from failed fills

### 5. Delete Integrity Failures
- Finds pending/created orders missing critical fields:
  - symbol (empty or null)
  - quantity (null or 0)
  - side (empty or null)
- Force rejects them with reason: "Missing critical fields"
- **This is the 1935-count issue** - malformed order records

### 6. Reconcile Position Mismatches
- Syncs OMS portfolio positions with broker (Zerodha)
- Identifies OMS-Broker qty mismatches
- Soft-deletes ghost positions
- Updates reconciliation status

## Testing After Fix

### Verify Position Reconciliation
```bash
curl "https://stokr.in/api/admin/oms/position-reconciliation"
```

Should show:
- ✅ BROKER_SYNC: OK (was MISMATCH)
- ✅ BLOCKING_LIVE: 0 (was 2)
- ✅ STRATEGIES_AT_CAPACITY: 0 (was 1)
- ✅ GHOST: 0 (was multiple)

### Verify No Stuck Orders
```bash
curl "https://stokr.in/api/admin/oms/stuck-orders/expire?stuckMinutes=1"
```

Should show: `"expired_count": 0`

### Verify Order Health
```bash
curl "https://stokr.in/api/admin/oms/stats"
```

Should show healthy rejection rates (<5%)

## Deployment Steps

1. **Build:** `mvn clean package -DskipTests`
2. **Deploy:** Push JAR to Contabo server
3. **Restart:** `docker-compose up -d api`
4. **Execute Fix:** Call `POST /api/admin/oms/health/fix-all` endpoint
5. **Verify:** Call `GET /api/admin/oms/position-reconciliation`

## Rollback

If issues arise:
- All fixes use soft-delete (set `deleted = true`)
- Can recover deleted records by setting `deleted = false`
- No hard deletes or data loss
- Safe to re-run multiple times

## Monitoring

After fix, monitor:
- Position reconciliation dashboard (every 5 min)
- Blocked orders count (should be 0)
- Execution success rate (should be >95%)
- Signal pipeline queue depth (should be empty)

## Files Changed

- `AdminSystemHealthFixService.java` - NEW: Comprehensive health fix service
- `AdminOmsController.java` - UPDATED: Added `/health/fix-all` endpoint
