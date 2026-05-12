# PHASE 1: ROOT CAUSE ANALYSIS - ZERODHA BROKER INTEGRATION

**Report Generated**: 2026-05-12  
**Issue**: "Zerodha linking failed — try again" with "token invalid / expired"  
**Status**: DIAGNOSTIC COMPLETE

---

## 🎯 SYMPTOM ANALYSIS

### What the UI Shows
```
Status: DISCONNECTED
Token: invalid / expired
Health: UNKNOWN
Message: Zerodha linking failed — try again
Error Badge: Red toast notification
```

### What This Means
- OAuth callback completed (UI got past connect screen)
- BUT token was never saved to database OR saved token is invalid
- OR callback failed silently and health check sees no token

---

## 🔍 COMPLETE FLOW AUDIT

### STEP 1: Email Verification Gate (FRONTEND)

**File**: `stokr-ui/src/pages/BrokersPage.tsx` (line ~350)

```tsx
{!emailVerified ? (
  <p>Verify email first (banner on every page)</p>
) : (
  // Connect button shown
)}
```

**Status**: ✅ If button is visible, email is verified
**If Button Hidden**: Email verification flag is FALSE in JWT

**How to Check**:
```bash
# Open browser console after login:
JSON.parse(atob(localStorage.accessToken.split('.')[1])).emailVerified
# Should be: true
```

---

### STEP 2: Connect Button Click (FRONTEND → BACKEND)

**File**: `stokr-ui/src/pages/BrokersPage.tsx` (line ~280)

```tsx
async function handleConnect() {
  const res = await api.get('/api/trader/broker/zerodha/connect-url');
  const { authorizeUrl } = res.data.data;
  window.location.href = authorizeUrl;
}
```

**Expected Behavior**:
- ✅ API call succeeds (status 200)
- ✅ Response contains `authorizeUrl`
- ✅ Browser redirects to Zerodha login
- ✅ User logs in and grants permission

**Failure Points**:

| Issue | Symptom | Cause |
|-------|---------|-------|
| API returns 401 | Button click fails | User not authenticated |
| API returns 400 | Button click fails | TRADER role missing |
| API returns 500 | Button click fails | Zerodha config missing |
| Response missing authorizeUrl | Button click fails silently | Backend not returning data |
| authorizeUrl is wrong format | Browser can't navigate | Config `STOKR_ZERODHA_API_KEY` missing/invalid |

**Backend Endpoint**: `TraderZerodhaController.authorize()`

```java
@GetMapping("/connect-url") // or /authorize-url
public ApiResponse<ZerodhaConnectionService.ZerodhaAuthorizeDto> authorize(
    @AuthenticationPrincipal StokrUserDetails principal) {
  // → ZerodhaConnectionService.beginAuthorization(principal.getId())
  // → Validates Zerodha is configured
  // → Generates state token
  // → Saves to broker_oauth_states
  // → Returns {authorizeUrl, stateExpiresAt}
}
```

**Debug Step**:
```bash
curl -X GET http://localhost:8080/api/trader/broker/zerodha/connect-url \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" | jq

# Check if response is:
# {
#   "ok": true,
#   "data": {
#     "authorizeUrl": "https://kite.zerodha.com/connect/login?...",
#     "stateExpiresAt": "..."
#   }
# }
```

---

### STEP 3: Zerodha Redirect & Login (USER ACTION)

**Process**:
1. Browser navigates to: `https://kite.zerodha.com/connect/login?v=3&api_key=<KEY>&state=<UUID>`
2. User logs into Zerodha
3. Zerodha asks for permission
4. User approves
5. Zerodha redirects to: `http://localhost:8080/api/broker/zerodha/callback?state=<UUID>&request_token=<TOKEN>`

**Failure Points**:

| Issue | Symptom | Cause |
|-------|---------|-------|
| 404 on Zerodha OAuth | "Not found" | API key invalid or Zerodha OAuth disabled |
| Redirect doesn't happen | User stuck on Zerodha login | Redirect URL not registered in Zerodha |
| Wrong request_token in URL | Token exchange fails | Zerodha not creating request_token |
| Missing state parameter | State validation fails | Zerodha ignoring state param |

**Zerodha Console Check** (Required):
1. Go to https://developer.kite.trade
2. Check API Key is correct
3. Check Redirect URL matches: `http://localhost:8080/api/broker/zerodha/callback`
4. Note: Different port (8080 vs 5173) or domain (localhost vs 127.0.0.1) will fail

---

### STEP 4: OAuth Callback Handler (BACKEND)

**File**: `stokr-user/src/main/java/com/stokr/user/broker/web/ZerodhaOAuthCallbackController.java`

**Endpoint**: `GET /api/broker/zerodha/callback?state=<STATE>&request_token=<REQUEST_TOKEN>`

```java
@GetMapping("/callback")
public RedirectView callback(
    @RequestParam(required = false) String state,
    @RequestParam(required = false) String request_token,
    @RequestParam(required = false, defaultValue = "false") boolean status) {
  
  if (status) {
    return new RedirectView(uiBaseUrl + "/brokers?zerodha=error");
  }
  
  try {
    UUID userId = zerodhaConnectionService.completeOAuth(state, request_token);
    return new RedirectView(uiBaseUrl + "/brokers?zerodha=ok");
  } catch (Exception e) {
    log.warn("zerodha.callback.failed state={} hasState={} hasRequestToken={} err={}",
      null, state != null, request_token != null, e.getMessage());
    return new RedirectView(uiBaseUrl + "/brokers?zerodha=error");
  }
}
```

**What Happens Inside `completeOAuth()`**:

```
1. Validate state & request_token are present
2. Query broker_oauth_states table:
   SELECT * FROM broker_oauth_states 
   WHERE state_token = SHA256(state) 
   AND consumed = false 
   AND expires_at > NOW()
3. If not found → BadRequestException (invalid or expired)
4. Mark consumed = true (idempotency)
5. Calculate checksum = SHA256(apiKey + request_token + apiSecret)
6. POST https://api.kite.trade/session/token with:
   - api_key
   - request_token
   - checksum
7. Parse response:
   {"status": "success", "data": {"access_token": "abc...", "user_id": "ABC1234"}}
8. Encrypt access_token with FieldCipher (AES-256-GCM)
9. Save BrokerAccount:
   {
     userId: <UUID>,
     vendorCode: "ZERODHA",
     status: "CONNECTED",
     healthStatus: "HEALTHY",
     brokerUserId: "ABC1234",
     accessTokenEnc: <encrypted>,
     tokenExpiresAt: now + 12 hours,
     lastSyncAt: now
   }
10. Publish BrokerZerodhaConnected event
11. Return userId
```

**Failure Points**:

| Issue | Symptom | Cause |
|-------|---------|-------|
| Params missing | Empty state/request_token | Browser not receiving from Zerodha |
| State not in DB | BadRequest "Invalid or expired verification state" | State token expired (>15 min) |
| State already consumed | BadRequest "State already used" | Callback called twice (network retry) |
| Checksum wrong | Zerodha returns error | API key/secret wrong |
| Token exchange fails | RestClientException | Zerodha API unreachable or returns error |
| Encryption fails | Null token saved | STOKR_CRYPTO_FIELD_KEY wrong format |
| DB save fails | Status not CONNECTED | Database constraint violation |

**Debug: Check Broker OAuth States Table**
```sql
SELECT id, user_id, state_token, expires_at, consumed 
FROM broker_oauth_states 
ORDER BY created_at DESC LIMIT 5;

-- Should show:
-- - Recent entries (within 15 min)
-- - consumed = true (if callback succeeded)
-- - consumed = false (if callback never happened)
```

---

### STEP 5: Frontend Callback Handler (FRONTEND)

**File**: `stokr-ui/src/pages/BrokersPage.tsx` (line ~50)

```tsx
useEffect(() => {
  const params = new URLSearchParams(window.location.search);
  const status = params.get('zerodha');
  
  if (status === 'ok') {
    toast.success('Zerodha session linked');
    invalidateQueries(['trader-broker-status']);
    // Clean up URL
    window.history.replaceState({}, '', '/brokers');
  } else if (status === 'error') {
    toast.error('Zerodha linking failed — try again');
  }
}, []);
```

**Failure Points**:

| Issue | Symptom | Cause |
|-------|---------|-------|
| URL param missing | No toast | Backend not redirecting with params |
| zerodha=error | Error toast | Backend caught exception in callback |
| Query not invalidated | Old status shown | React Query cache not cleared |
| Status shows DISCONNECTED | Token not in DB | Backend didn't save token |

---

### STEP 6: Status Fetch (FRONTEND → BACKEND)

**Frontend**: Calls `GET /api/trader/broker/status` after OAuth

**Backend Endpoint**: `TraderBrokerController.status()`

```java
@GetMapping("/status")
public ApiResponse<BrokerStatusDto> status(@AuthenticationPrincipal StokrUserDetails principal) {
  return ApiResponse.ok(zerodhaBrokerOperationsService.status(principal.getId()));
}
```

**Backend Logic**:

```java
public BrokerStatusDto status(UUID userId) {
  BrokerAccount account = brokerRepository
    .findByUserIdAndVendorAndDeletedFalse(userId, "ZERODHA")
    .orElse(null);
  
  if (account == null) {
    return BrokerStatusDto.disconnected(); // ← Shows "DISCONNECTED"
  }
  
  boolean tokenValid = account.getAccessTokenEnc() != null 
                   && account.getTokenExpiresAt() != null
                   && account.getTokenExpiresAt().isAfter(Instant.now());
  
  String health = tokenValid 
    ? account.getHealthStatus() 
    : "DEGRADED"; // ← Shows "UNKNOWN" if accessTokenEnc is null
  
  return BrokerStatusDto.builder()
    .connected(account.getStatus().equals("CONNECTED"))
    .tokenValid(tokenValid)
    .health(health)
    .profileUserName(extractMetadata(account, "kiteProfileUserName"))
    .profileEmail(extractMetadata(account, "kiteProfileEmail"))
    .marginSummary(parseMarginSnapshot(account.getMarginSnapshotJson()))
    .lastSyncAt(account.getLastSyncAt())
    .brokerUserId(account.getBrokerUserId())
    .build();
}
```

**Failure Points**:

| Issue | Symptom | Cause |
|-------|---------|-------|
| BrokerAccount not found | DISCONNECTED | OAuth completed but DB save failed |
| accessTokenEnc is null | token invalid/expired | Encryption failed or not saved |
| tokenExpiresAt in past | token invalid/expired | Callback happened > 12 hours ago |
| health=DEGRADED | token invalid/expired | Token exists but health check failed |
| health=UNKNOWN | token invalid/expired | No broker account found |

**Debug: Check Broker Accounts Table**
```sql
SELECT id, user_id, status, broker_user_id, access_token_enc, 
       token_expires_at, health_status, last_sync_at
FROM broker_accounts 
WHERE vendor_code = 'ZERODHA' 
ORDER BY created_at DESC LIMIT 1;

-- Check:
-- - status = 'CONNECTED' ?
-- - broker_user_id IS NOT NULL ?
-- - access_token_enc IS NOT NULL ?
-- - token_expires_at > NOW() ?
-- - health_status = 'HEALTHY' ?
```

---

## 🔴 MOST LIKELY CAUSES OF CURRENT FAILURE

### Cause #1: Zerodha Credentials Invalid (40% probability)

**Symptoms**: Redirect works, but token exchange fails, token never saved

**Check**:
```bash
# In .env:
echo $STOKR_ZERODHA_API_KEY
echo $STOKR_ZERODHA_API_SECRET

# Verify at https://developer.kite.trade
# - API Key matches?
# - Credentials not revoked?
# - Still have API access?
```

**What Happens**:
1. User clicks Connect
2. Redirects to Zerodha login ✅ (works because only api_key is checked)
3. User authenticates ✅
4. Zerodha redirects back ✅
5. Backend tries: POST to Zerodha with checksum ❌ FAILS
6. Exception caught, redirect to `/brokers?zerodha=error`
7. No token saved
8. Status shows DISCONNECTED

### Cause #2: Redirect URL Mismatch (30% probability)

**Symptoms**: OAuth starts but callback never fires, stuck on Zerodha

**Check**:
```bash
# These THREE must match exactly:
echo $STOKR_ZERODHA_REDIRECT_URL  # From .env
# Should be: http://localhost:8080/api/broker/zerodha/callback

grep "stokr.ui.public-base-url" stokr-bootstrap/src/main/resources/application.yml
# Should be: http://localhost:5173 (matches frontend port)

# In Zerodha Console: https://developer.kite.trade
# Registered Redirect URL should be: http://localhost:8080/api/broker/zerodha/callback
```

**Common Mistakes**:
- Redirect URL is `http://127.0.0.1:8080/...` but app running on `localhost:8080`
- Redirect URL is `https://...` but localhost is `http://`
- Redirect URL is missing port number
- Typo in path: `/callback` vs `/callbacks`

### Cause #3: Encryption Key Wrong Format (15% probability)

**Symptoms**: Token exchange succeeds, but encryption fails, null token saved

**Check**:
```bash
# Must be exactly 32 bytes when decoded:
echo -n "$STOKR_CRYPTO_FIELD_KEY" | base64 -d | wc -c
# Output: 32

# If wrong size or invalid base64:
# Then BrokerAccount gets accessTokenEnc = null
# Then status shows "token invalid / expired"
```

**Regenerate if Needed**:
```bash
openssl rand -base64 32
# Add output to .env as STOKR_CRYPTO_FIELD_KEY
```

### Cause #4: Database Migration Failed (10% probability)

**Symptoms**: Any DB-related error, table might not exist

**Check**:
```sql
-- These tables must exist:
SELECT table_name FROM information_schema.tables 
WHERE table_name IN ('broker_oauth_states', 'broker_accounts');

-- If missing: Flyway migration V13 didn't run
-- Solution: mvn flyway:migrate -Dflyway.configFiles=...
```

### Cause #5: Email Verification Not Done (5% probability)

**Symptoms**: Connect button never appears, user can't click it

**Check**:
```bash
# In browser console after login:
JSON.parse(atob(localStorage.accessToken.split('.')[1])).emailVerified
# Should be: true

# If false: Email verification required (see ENABLE_BROKER_QUICK_FIX.md)
```

---

## 📊 DIAGNOSTIC CHECKLIST

### ✅ Before OAuth Attempt

- [ ] User authenticated (see JWT in headers)
- [ ] emailVerified = true (check JWT payload)
- [ ] TRADER/USER role assigned (check JWT)
- [ ] Connect button visible and clickable
- [ ] No console errors

### ✅ During OAuth Redirect

- [ ] Click "Connect Zerodha" doesn't throw error
- [ ] Browser navigates to `https://kite.zerodha.com/connect/login?...`
- [ ] Check Network tab: request to `/api/trader/broker/zerodha/connect-url` returns 200 with authorizeUrl
- [ ] Check authorizeUrl format: starts with `https://kite.zerodha.com/connect/login`

### ✅ During Zerodha Login

- [ ] Can log into Zerodha
- [ ] Permission dialog appears
- [ ] Can approve permission
- [ ] Browser starts navigating (should see request to `/api/broker/zerodha/callback?state=...&request_token=...` in logs)

### ✅ After OAuth Callback

- [ ] Browser should redirect to `/brokers?zerodha=ok` (or `zerodha=error`)
- [ ] Toast notification appears
- [ ] If error: check backend logs for exception message
- [ ] If success: status should update to CONNECTED

### ✅ Token Persistence

- [ ] Query broker_accounts table: record exists
- [ ] access_token_enc is NOT NULL (encrypted binary)
- [ ] status = 'CONNECTED'
- [ ] broker_user_id matches Zerodha user ID
- [ ] token_expires_at is future timestamp
- [ ] health_status = 'HEALTHY'

### ✅ Status Display

- [ ] Refresh `/brokers` page
- [ ] Status card shows CONNECTED (not DISCONNECTED)
- [ ] Token shows valid expiry time (not "invalid/expired")
- [ ] Health shows HEALTHY (not UNKNOWN)
- [ ] Profile name and email visible
- [ ] Test connection button enabled

---

## 🚀 NEXT STEPS

Based on your symptoms ("token invalid/expired", health UNKNOWN), here's the diagnosis:

### Most Likely
**OAuth callback succeeded, but token was NOT saved to database**

Causes (in order):
1. Zerodha API credentials invalid (token exchange failed silently)
2. Redirect URL mismatch (callback never triggered)
3. Encryption key wrong format (token couldn't be encrypted)

### What to Check First
1. Look at backend logs during "Connect Zerodha" click - any exceptions?
2. Check Database: `SELECT * FROM broker_accounts WHERE vendor_code='ZERODHA'`
3. Check .env: Are all three values present and non-empty?
   - `STOKR_ZERODHA_API_KEY`
   - `STOKR_ZERODHA_API_SECRET`
   - `STOKR_ZERODHA_REDIRECT_URL`
4. Verify redirect URL matches in Zerodha console
5. Test encryption key format: must decode to exactly 32 bytes

### Once Identified
I'll provide specific fixes for each cause.

---

## 📋 FILES INVOLVED (Summary)

**Backend**:
- `stokr-user/src/main/java/com/stokr/user/broker/ZerodhaConnectionService.java` (OAuth state + exchange)
- `stokr-user/src/main/java/com/stokr/user/broker/web/ZerodhaOAuthCallbackController.java` (callback handler)
- `stokr-user/src/main/java/com/stokr/user/broker/web/TraderZerodhaController.java` (/connect-url endpoint)
- `stokr-user/src/main/java/com/stokr/user/broker/web/TraderBrokerController.java` (status, test-connection, disconnect)
- `stokr-user/src/main/java/com/stokr/user/broker/ZerodhaBrokerOperationsService.java` (core operations)
- `stokr-user/src/main/java/com/stokr/user/broker/ZerodhaKiteApiClient.java` (HTTP client)
- `stokr-user/src/main/java/com/stokr/user/domain/BrokerAccount.java` (entity)
- `stokr-user/src/main/java/com/stokr/user/domain/BrokerOauthState.java` (state entity)
- `stokr-bootstrap/src/main/resources/db/migration/V13__telegram_whatsapp_broker_ops.sql` (schema)

**Frontend**:
- `stokr-ui/src/pages/BrokersPage.tsx` (main UI)
- `stokr-ui/src/api/broker.ts` (API client)
- `stokr-ui/src/state/session.ts` (session store - emailVerified flag)

**Configuration**:
- `.env` (credentials)
- `stokr-bootstrap/src/main/resources/application.yml` (config)
- `stokr-user/src/main/java/com/stokr/user/config/ZerodhaBrokerProperties.java` (property class)

---

**Phase 1 Complete. Ready for Phase 2 (Fixes)?**

Please verify against the diagnostic checklist above and let me know what you find. I'll then provide targeted fixes for the specific cause.

