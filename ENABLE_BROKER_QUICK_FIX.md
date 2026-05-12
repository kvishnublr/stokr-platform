# Quick Fix: Enable Email Verification & Broker Connection

**Current Problem**: Email verification is required but email service isn't sending emails.

**Solution**: Implement in-app email verification for development/testing.

---

## 🚀 OPTION 1: Direct Database Fix (Fastest - 30 seconds)

### Step 1: Connect to PostgreSQL
```bash
# Using psql (if installed)
psql -h localhost -U postgres -d stokr_platform

# Or using Docker
docker exec -it stokr-postgres psql -U postgres -d stokr_platform
```

### Step 2: Mark Email as Verified
```sql
-- Replace your-email@example.com with your actual email
UPDATE auth_users SET email_verified = true WHERE email = 'your-email@example.com';

-- Verify it worked
SELECT id, email, email_verified FROM auth_users WHERE email = 'your-email@example.com';
```

### Step 3: Logout and Login Again
```
1. Go to http://localhost:5173
2. Log out (if logged in)
3. Log back in
4. Email verification banner should be gone
5. Broker connection should now be available
```

---

## 🔧 OPTION 2: Implement In-App Verification (Better - 10 minutes)

### Step 1: Create Email Verification Service

Create file: `stokr-auth/src/main/java/com/stokr/auth/service/EmailVerificationService.java`

```java
package com.stokr.auth.service;

import com.stokr.auth.domain.AuthEmailVerificationToken;
import com.stokr.auth.domain.AuthUser;
import com.stokr.auth.repository.AuthEmailVerificationTokenRepository;
import com.stokr.auth.repository.AuthUserRepository;
import com.stokr.common.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EmailVerificationService {

    private final AuthUserRepository userRepository;
    private final AuthEmailVerificationTokenRepository tokenRepository;
    
    @Value("${stokr.auth.email-verification-token-ttl-minutes:60}")
    private int tokenTtlMinutes;

    @Transactional
    public String generateVerificationToken(UUID userId) {
        AuthUser user = userRepository.findById(userId)
            .orElseThrow(() -> new NotFoundException("User not found"));
        
        // Create token
        String tokenValue = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(new SecureRandom().generateSeed(32));
        
        AuthEmailVerificationToken token = new AuthEmailVerificationToken();
        token.setId(UUID.randomUUID());
        token.setUserId(userId);
        token.setToken(tokenValue);
        token.setExpiresAt(Instant.now().plus(tokenTtlMinutes, ChronoUnit.MINUTES));
        token.setUsed(false);
        
        tokenRepository.save(token);
        return tokenValue;
    }

    @Transactional
    public void markEmailVerified(UUID userId) {
        AuthUser user = userRepository.findById(userId)
            .orElseThrow(() -> new NotFoundException("User not found"));
        
        user.setEmailVerified(true);
        userRepository.save(user);
    }

    @Transactional
    public boolean validateAndUseToken(String token) {
        AuthEmailVerificationToken verificationToken = tokenRepository.findByToken(token)
            .orElseThrow(() -> new NotFoundException("Invalid verification token"));
        
        if (verificationToken.isUsed()) {
            throw new IllegalStateException("Token already used");
        }
        
        if (Instant.now().isAfter(verificationToken.getExpiresAt())) {
            throw new IllegalStateException("Token expired");
        }
        
        // Mark token as used and email as verified
        verificationToken.setUsed(true);
        tokenRepository.save(verificationToken);
        
        markEmailVerified(verificationToken.getUserId());
        return true;
    }
}
```

### Step 2: Add Controller Endpoint

Update: `stokr-auth/src/main/java/com/stokr/auth/web/AuthController.java`

Add this endpoint:
```java
@PostMapping("/verify-email-dev")
@PreAuthorize("isAuthenticated()")
public ApiResponse<Void> verifyEmailDev(@AuthenticationPrincipal StokrUserDetails principal) {
    // ONLY FOR DEVELOPMENT - Remove before production
    authService.markEmailVerified(UUID.fromString(principal.getId()));
    return ApiResponse.ok(cid());
}
```

Then add method to AuthService:
```java
@Transactional
public void markEmailVerified(UUID userId) {
    AuthUser user = userRepository.findById(userId)
        .orElseThrow(() -> new NotFoundException("User not found"));
    user.setEmailVerified(true);
    userRepository.save(user);
}
```

### Step 3: Create UI Component for Email Verification

Create file: `stokr-ui/src/components/EmailVerificationBanner.tsx`

```tsx
import { AlertCircle, Check } from "lucide-react";
import { useState } from "react";
import { toast } from "sonner";
import { api } from "../api/client";
import { useSessionStore } from "../state/session";

export function EmailVerificationBanner() {
  const emailVerified = useSessionStore((s) => s.emailVerified);
  const [verifying, setVerifying] = useState(false);

  if (emailVerified) return null;

  const handleVerify = async () => {
    setVerifying(true);
    try {
      await api.post("/api/auth/verify-email-dev");
      toast.success("Email verified!");
      // Refresh session
      useSessionStore.setState({ emailVerified: true });
    } catch (err) {
      toast.error("Failed to verify email");
    } finally {
      setVerifying(false);
    }
  };

  return (
    <div className="w-full border-b border-amber-900/30 bg-amber-950/50 px-4 py-3">
      <div className="flex items-center justify-between text-sm text-amber-100">
        <div className="flex items-center gap-2">
          <AlertCircle className="h-4 w-4" />
          <span>Email verification pending (required for broker connection)</span>
        </div>
        <button
          onClick={handleVerify}
          disabled={verifying}
          className="rounded bg-amber-600 px-3 py-1 hover:bg-amber-700 disabled:opacity-50"
        >
          {verifying ? "Verifying..." : "Verify Now"}
        </button>
      </div>
    </div>
  );
}
```

### Step 4: Update ShellLayout to show banner

In `stokr-ui/src/layout/ShellLayout.tsx`, add to the render:

```tsx
import { EmailVerificationBanner } from "../components/EmailVerificationBanner";

export function ShellLayout() {
  // ... existing code ...
  
  return (
    <AppShell>
      <EmailVerificationBanner />
      {/* rest of layout */}
    </AppShell>
  );
}
```

### Step 5: Rebuild and Test

```bash
# Backend
mvn clean install -DskipTests

# Restart
mvn -pl stokr-bootstrap spring-boot:run
```

```bash
# Frontend
cd stokr-ui
npm run build
npm run dev
```

Now you'll see a "Verify Now" button in the banner that instantly verifies your email without needing email.

---

## 🔑 Configuration for Real Email (Production)

### Option A: SendGrid

1. Get API key from https://sendgrid.com
2. Add to `.env`:
   ```bash
   SENDGRID_API_KEY=SG.xxxxxxxxxxxx
   ```

3. Update `AuthService.register()` to call email service

### Option B: Amazon SES

1. Configure AWS credentials
2. Add to `.env`:
   ```bash
   AWS_REGION=us-east-1
   AWS_ACCESS_KEY_ID=AKIA...
   AWS_SECRET_ACCESS_KEY=...
   ```

### Option C: SMTP (Any provider)

Add to `application.yml`:
```yaml
spring:
  mail:
    host: smtp.gmail.com
    port: 587
    username: your-email@gmail.com
    password: your-app-password
    properties:
      mail:
        smtp:
          auth: true
          starttls:
            enable: true
            required: true
```

---

## ✅ Complete Flow After Fix

```
1. User logs in
   └─ If email not verified: Show banner "Verify Now"
   
2. User clicks "Verify Now"
   └─ POST /api/auth/verify-email-dev
   └─ Backend marks email_verified = true
   └─ Frontend updates session
   
3. Refresh page or banner disappears
   └─ Email verification banner gone
   
4. Navigate to /brokers
   └─ Zerodha connection card is now fully visible
   └─ "Connect Zerodha" button enabled
   
5. Click "Connect Zerodha"
   └─ Redirects to Zerodha login
   └─ After auth, returns with access token
   └─ Shows "Connected" status
   
6. Click "Test connection"
   └─ Validates broker connection
   └─ Shows account details & funds
   └─ Status changes to HEALTHY
```

---

## 🧪 Testing Checklist

- [ ] **1. Email Verification**
  ```bash
  # Direct fix: Check database
  SELECT email, email_verified FROM auth_users;
  ```
  
- [ ] **2. Login After Verification**
  - Log out and back in
  - Check JWT token for `"emailVerified": true`
  
- [ ] **3. Broker UI Visible**
  - Navigate to `/brokers`
  - "Connect Zerodha" button should be visible (not grayed out)
  
- [ ] **4. Configure Zerodha Credentials**
  - Set in `.env`:
    ```bash
    STOKR_ZERODHA_API_KEY=your_key
    STOKR_ZERODHA_API_SECRET=your_secret
    STOKR_CRYPTO_FIELD_KEY=your_32byte_key
    ```
  
- [ ] **5. OAuth Flow**
  - Click "Connect Zerodha"
  - Should redirect to Zerodha login page
  - After auth, should return with "Connected" status
  
- [ ] **6. Test Connection**
  - Click "Test connection" button
  - Should show account details and funds
  - Status should show "HEALTHY"

---

## 🐛 Common Issues & Fixes

### Issue: "Verify email first" still showing after marking verified
**Fix**: JWT is cached. Solution:
1. Clear browser cookies/localStorage
2. Log out completely
3. Log back in
4. JWT will be refreshed with new emailVerified flag

### Issue: Broker connection button still grayed out
**Fix**: Check frontend state:
```tsx
// In browser console:
JSON.stringify(useSessionStore.getState())
// Look for "emailVerified": true
```

### Issue: OAuth redirects but says "Invalid API credentials"
**Fix**: Verify credentials:
1. Check `.env` for correct API Key/Secret
2. Restart application after updating `.env`
3. Check Zerodha console for rate limiting

---

## 📊 Summary

| Step | Time | Difficulty |
|------|------|-----------|
| **Quick Fix (DB)** | 30 sec | Very Easy |
| **In-App Verification** | 10 min | Easy |
| **Real Email Service** | 1 hour | Medium |
| **Full Broker Setup** | 15 min | Easy |
| **Live Trading** | 1-2 days | Hard |

**Recommended**: Start with Quick Fix (1), then implement Option 2 (In-App Verification) for better UX.

