# Zerodha Broker Connection Setup Guide

**Status**: Currently showing "Disconnected" with "token invalid / expired"  
**Reason**: Email not verified + OAuth token not configured

---

## 📋 Prerequisites

### 1. Zerodha Account & API Keys
You need a Zerodha account with Kite Connect enabled:

1. Go to https://kite.zerodha.com
2. Login to your account
3. Go to **Settings → API Consents** (or https://developer.kite.trade)
4. Generate API credentials:
   - You'll receive an **API Key**
   - You'll receive an **API Secret**

**Example:**
```
API Key:    abc123def456ghi789
API Secret: secret123abc456def789ghi
```

### 2. Email Verification (Required)

The platform requires **email verification before broker connection**. This is enforced in:
- `stokr-ui/src/pages/BrokersPage.tsx` - Frontend UI guard
- `UserProfileService` - Backend tracks `emailVerified` flag

**Status**: Check JWT token to see if `emailVerified: true`

---

## 🔧 Configuration Steps

### Step 1: Set Zerodha Credentials in .env

Edit `.env` file and add:

```bash
# ===== ZERODHA BROKER CONFIGURATION =====
STOKR_ZERODHA_API_KEY=your_api_key_here
STOKR_ZERODHA_API_SECRET=your_api_secret_here
STOKR_ZERODHA_REDIRECT_URL=http://localhost:8080/api/broker/zerodha/callback
STOKR_UI_PUBLIC_BASE_URL=http://localhost:5173

# For production, use your actual domain:
# STOKR_ZERODHA_REDIRECT_URL=https://api.yourdomain.com/api/broker/zerodha/callback
# STOKR_UI_PUBLIC_BASE_URL=https://app.yourdomain.com
```

Important for local dev: use one host style consistently (`localhost` or `127.0.0.1`) across UI URL, API URL, and the redirect URL configured in Kite Connect app settings.
Kite dashboard **Redirect URL** must exactly match `STOKR_ZERODHA_REDIRECT_URL` (recommended local value: `http://localhost:8080/api/broker/zerodha/callback`).
The app injects the OAuth `state` into this callback URL at runtime while generating the Kite login URL (callback becomes `.../callback?state=<generated-state>` for that auth attempt).

### Step 2: Set AES-256 Encryption Key

The platform encrypts broker tokens with AES-256-GCM. Generate a key:

```bash
# Generate a 32-byte random key (256-bit)
openssl rand -base64 32
```

Output will look like:
```
aBcD+EfGhIjKlMnOpQrStUvWxYz1a2b3c4D5e6F7g8H9i0j1K2l3M4n5O6p7Q8r9S=
```

Add to `.env`:
```bash
# ===== ENCRYPTION CONFIGURATION =====
STOKR_CRYPTO_FIELD_KEY=aBcD+EfGhIjKlMnOpQrStUvWxYz1a2b3c4D5e6F7g8H9i0j1K2l3M4n5O6p7Q8r9S=
```

### Step 3: Update application.yml (if not using .env)

If using `stokr-bootstrap/src/main/resources/application.yml`:

```yaml
stokr:
  broker:
    zerodha:
      api-key: ${STOKR_ZERODHA_API_KEY:}
      api-secret: ${STOKR_ZERODHA_API_SECRET:}
      redirect-url: ${STOKR_ZERODHA_REDIRECT_URL:http://localhost:8080/api/broker/zerodha/callback}
      test-order-enabled: true          # Allow test orders
      test-order-dry-run: true          # Don't actually place orders
      sync-enabled: true                # Auto-sync funds every 5 min
      sync-ms: 300000                   # 5 minutes
  
  crypto:
    field-key: ${STOKR_CRYPTO_FIELD_KEY:}  # AES-256 key for token encryption
```

### Step 4: Verify Email (Backend)

**Current Implementation**: Email verification is **NOT YET WIRED** to actual email service.

**What exists**:
- Email verification flag in `AuthUser.emailVerified` (database)
- JWT includes `emailVerified` claim
- Frontend checks this flag before showing broker connection UI

**What's missing**:
- Email sending service (SendGrid/SES integration)
- Email verification endpoint to mark email as verified

**Temporary workaround** (for local development):

Option A: Update database directly
```sql
UPDATE auth_user SET email_verified = true WHERE email = 'your-email@example.com';
```

Option B: Create a verification endpoint (temporary):
```java
// Add to AuthController temporarily:
@PostMapping("/api/auth/verify-email-bypass")
public ResponseEntity<?> verifyEmailBypass(@AuthenticationPrincipal StokrUserDetails user) {
    // REMOVE THIS AFTER EMAIL SERVICE IS WIRED
    authUserRepository.markEmailVerified(user.getUserId());
    return ResponseEntity.ok("Email verified");
}
```

---

## 🚀 Complete Startup Sequence

### Option A: Using Docker Compose

```bash
# 1. Update .env file with all credentials above

# 2. Start infrastructure (PostgreSQL, Redis, RabbitMQ)
docker compose --env-file .env up -d

# 3. Start the application
docker compose --env-file .env --profile app up --build

# 4. Access UI at http://localhost:3000
```

### Option B: Local Development (Java + npm)

```bash
# 1. Update .env file with all credentials

# 2. Start infrastructure
docker compose --env-file .env up -d

# 3. Build backend
mvn clean install -DskipTests

# 4. Start backend
mvn -pl stokr-bootstrap spring-boot:run

# 5. In another terminal, start frontend
cd stokr-ui
npm run dev

# 6. Access at http://localhost:5173
```

---

## 🔐 OAuth Flow (What Happens)

### Step-by-Step Process

```
1. User clicks "Connect Zerodha" button
   └─ Frontend calls GET /api/trader/broker/zerodha/connect-url
   
2. Backend generates OAuth state:
   └─ Random stateToken (UUID)
   └─ Store in broker_oauth_states table
   └─ Set 15-minute expiration
   └─ Return authorize URL: https://kite.zerodha.com/connect/login?...
   
3. Frontend redirects user to Zerodha login page
   └─ User authenticates with Zerodha
   └─ User grants permission to stokr-platform
   
4. Zerodha redirects back to callback:
   └─ GET /api/broker/zerodha/callback?state=<generated-state>&request_token=<token>
      (`state` is injected by app flow into `redirect_url`; callback still validates it against DB state store)
   
5. Backend validates & exchanges token:
   ├─ Verify state token from table (not expired, not consumed)
   ├─ Mark state as consumed
   ├─ Create checksum: SHA256(apiKey + requestToken + apiSecret)
   ├─ POST to https://api.kite.trade/session/token with checksum
   ├─ Zerodha returns access_token
   ├─ Encrypt access_token with AES-256-GCM
   ├─ Store in BrokerAccount.accessTokenEnc
   ├─ Set status = CONNECTED, health = HEALTHY
   ├─ Set tokenExpiresAt = now + 12 hours
   └─ Redirect to /brokers?zerodha=ok
   
6. Frontend shows "Connected" status
   └─ Broker account shows:
      - Account ID
      - Profile name
      - Email
      - Funds snapshot
      - Token valid until [timestamp]
```

---

## ✅ Broker Connection Status Checks

### Check 1: Email Verified
**Location**: JWT token or `/api/auth/me` endpoint
```json
{
  "emailVerified": true,  // Must be true
  "email": "user@example.com"
}
```

### Check 2: Zerodha Credentials Configured
**Check**: Application logs on startup
```
2026-05-12 10:15:30 INFO [bootstrap] Zerodha API Key configured: abc123***
2026-05-12 10:15:30 INFO [bootstrap] Zerodha API Secret configured: ✓
```

### Check 3: Encryption Key Configured
**Check**: Application logs on startup
```
2026-05-12 10:15:30 INFO [bootstrap] Field encryption enabled: ✓ (AES-256-GCM)
```

### Check 4: OAuth State Cleared
**Check**: Database
```sql
SELECT COUNT(*) FROM broker_oauth_states WHERE NOT consumed;
-- Should be 0 (no pending states)
```

### Check 5: Test Connection
**Endpoint**: `POST /api/trader/broker/test-connection`
```bash
curl -X POST http://localhost:8080/api/trader/broker/test-connection \
  -H "Authorization: Bearer <access-token>"
```

**Response on success**:
```json
{
  "connected": true,
  "health": "HEALTHY",
  "profileUserName": "ABC1234",
  "profileEmail": "trader@example.com",
  "marginSummary": {
    "net": 100000,
    "available": 85000
  },
  "lastSyncAt": "2026-05-12T10:30:00Z"
}
```

---

## 🐛 Troubleshooting

### Issue 1: "Verify email first (banner on every page)"

**Root cause**: `emailVerified` flag not set in JWT

**Solution**:
1. Check JWT token: `echo $token | jq -R 'split(".") | .[1] | @base64d' | jq`
2. Look for `"emailVerified": true`
3. If false, run SQL:
   ```sql
   UPDATE auth_user SET email_verified = true WHERE user_id = '<your-user-id>';
   ```
4. Get new token: Log out and log back in

### Issue 2: "Token invalid / expired" but broker not disconnected

**Root cause**: Token in database is expired (12-hour TTL)

**Solution**: 
1. Click "Retry" button to re-authenticate
2. This will trigger the OAuth flow again
3. Generate new token with fresh 12-hour expiration

### Issue 3: OAuth redirect fails with 401/403

**Root cause**: API Key or API Secret wrong

**Solution**:
1. Verify credentials in `.env`
2. Restart application: `mvn -pl stokr-bootstrap spring-boot:run`
3. Check logs for:
   ```
   ERROR: Zerodha OAuth token exchange failed: Invalid API credentials
   ```
4. Double-check Kite API credentials at https://developer.kite.trade

### Issue 4: "AES decryption failed" error

**Root cause**: Encryption key wrong or not set

**Solution**:
1. Verify `STOKR_CRYPTO_FIELD_KEY` in `.env`
2. Should be Base64-encoded 32 bytes
3. Regenerate if needed: `openssl rand -base64 32`
4. Restart application

### Issue 5: Funds/Margin always showing placeholder

**Root cause**: Margin sync not wired to real Kite API yet (Phase 1 stub)

**Status**: The `BrokerMarginSyncScheduler` runs but currently creates placeholder data
- **File**: `stokr-user/src/main/java/com/stokr/user/broker/BrokerMarginSyncScheduler.java`
- **Comment**: "TODO: Wire to real Kite API for account/margin fetch"

**What needs to be done**:
```java
// Current (Phase 1 stub):
public void sync() {
    // TODO: Call Kite API
    marginSnapshot.setEquity(100000);  // Hardcoded
}

// Should be:
public void sync() {
    KiteProfile profile = kiteApiClient.getProfile(account);
    KiteMargins margins = kiteApiClient.getMargins(account);
    marginSnapshot.setEquity(margins.getEquity().getNet());
}
```

**Workaround**: The funds snapshot updates every 5 minutes via scheduler. Real data will show after Kite API integration is complete.

---

## 📡 Live Trading After Broker Connection

Once broker is connected, the execution flow changes:

### Simulation Mode (Current)
```
Strategy Signal → OrderIntentProcessor → ExecutionSimulator → Simulated Fill
```

### Live Mode (After Connection)
```
Strategy Signal → OrderIntentProcessor → LiveTradingGate → ZerodhaKiteApiClient → Real Fill
```

**To enable live trading**:

1. Set config in `.env`:
   ```bash
   STOKR_LIVE_TRADING_ENABLED=true
   ```

2. Or in `application.yml`:
   ```yaml
   stokr:
     execution:
       live-trading-enabled: true
   ```

3. Restart application

4. Now strategy signals will place **real orders** on Zerodha

⚠️ **WARNING**: Only enable after thorough testing!

---

## 📊 What Works After Setup

### ✅ Phase 1: Broker Connection
- [x] OAuth flow with Zerodha
- [x] Secure token storage (AES-256 encrypted)
- [x] Connection status display
- [x] Token expiration tracking
- [x] Test connection validation

### ⚠️ Phase 2: Account Information (Partial)
- [x] Profile sync (username, email)
- [x] Margin snapshot (schedule: every 5 min)
- [ ] **Real Kite API integration** (currently stubbed)

### ⚠️ Phase 3: Live Order Placement (Ready but needs testing)
- [x] OAuth flow working
- [x] Kite API client ready
- [x] Live trading gate implemented
- [ ] **End-to-end testing needed**
- [ ] **Order confirmation handling** (minimal)

---

## 🎯 Next Steps

1. **Immediate** (5 minutes):
   - Add API credentials to `.env`
   - Generate & add encryption key
   - Verify email in database (or implement email service)

2. **Start Application** (1 minute):
   ```bash
   mvn -pl stokr-bootstrap spring-boot:run
   cd stokr-ui && npm run dev
   ```

3. **Test Broker Connection** (5 minutes):
   - Click "Connect Zerodha"
   - Authenticate with Zerodha
   - Verify "Connected" status
   - Click "Test connection"

4. **Complete Margin Sync** (2-3 days):
   - Implement `ZerodhaKiteApiClient.getMargins()`
   - Wire real Kite API calls
   - Add error handling

5. **Test Live Trading** (1-2 days):
   - Place manual test order via UI
   - Create test strategy
   - Verify simulated trades → live orders

---

## 🔗 Useful Links

- **Zerodha API Docs**: https://kite.trade/
- **Kite Connect SDK**: https://github.com/zerodha/kiteconnect-java
- **OAuth Flow**: https://kite.trade/connect/login

**Configuration Reference**:
- [stokr-user/src/main/java/com/stokr/user/config/ZerodhaBrokerProperties.java](stokr-user/src/main/java/com/stokr/user/config/ZerodhaBrokerProperties.java)
- [stokr-bootstrap/src/main/resources/application.yml](stokr-bootstrap/src/main/resources/application.yml)

