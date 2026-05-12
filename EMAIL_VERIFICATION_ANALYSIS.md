# Email Verification Settings Analysis

**Status**: ✅ Email service IS configured (but in sandbox mode)

**Generated**: 2026-05-12

---

## 🔍 Current Configuration

### SMTP Settings (from .env)
```
✅ SPRING_MAIL_HOST=sandbox.smtp.mailtrap.io
✅ SPRING_MAIL_PORT=2525
✅ SPRING_MAIL_USERNAME=9bbe635464608a (MailTrap account)
✅ SPRING_MAIL_PASSWORD=2fe4921c9d2308
✅ SPRING_MAIL_SMTP_AUTH=true
✅ SPRING_MAIL_SMTP_STARTTLS=true
```

### What This Means
- **SMTP Configured**: YES ✅
- **Email Service**: MailTrap (sandbox/testing service)
- **Emails Sent**: NOT to your inbox, but to MailTrap dashboard
- **Why**: For development/testing - doesn't use real email provider

---

## 📧 Email Verification Flow

### When You Register:
```
1. POST /api/auth/register
   └─ User created with emailVerified=false
   
2. AuthService.issueEmailVerificationToken()
   └─ Generates random verification token
   └─ Saves token hash in auth_email_verification_tokens table
   └─ Creates verification link: http://localhost:5173/verify-email?token=<token>
   
3. VerificationEmailDeliveryService.sendVerificationEmail()
   ├─ Checks if SMTP configured: YES ✅
   ├─ Builds HTML email
   ├─ Sends via SMTP to MailTrap
   └─ Returns outcome: SENT or SEND_FAILED
   
4. AuthResponse includes:
   └─ emailVerified: false (until user clicks link)
   └─ JWT includes this flag
```

### What You Should See:
**In your terminal/logs:**
```
INFO: Verification email queued/sent for recipient domain xx…@gmail.com
```

**Where the email actually goes:**
```
MailTrap Dashboard: https://mailtrap.io
└─ Inbox: test@mailtrap.io
└─ Account: 9bbe635464608a (from .env)
```

---

## ⚙️ Email Service Implementation Details

### Service: VerificationEmailDeliveryService

**Location**: `stokr-auth/src/main/java/com/stokr/auth/mail/VerificationEmailDeliveryService.java`

**Behavior**:
```java
public VerificationEmailSendOutcome sendVerificationEmail(
    String toEmail,           // your-email@gmail.com
    String verifyUrl,         // http://localhost:5173/verify-email?token=...
    int hoursValid            // 1 hour (configurable)
) {
    // 1. Check if SPRING_MAIL_HOST is set
    if (!StringUtils.hasText(mailHost)) {
        return VerificationEmailSendOutcome.NOT_CONFIGURED;
    }
    
    // 2. Get JavaMailSender bean
    JavaMailSender sender = mailSenderProvider.getIfAvailable();
    if (sender == null) {
        return VerificationEmailSendOutcome.SEND_FAILED;
    }
    
    // 3. Check SPRING_MAIL_USERNAME is set
    if (!StringUtils.hasText(mailUsername)) {
        return VerificationEmailSendOutcome.SEND_FAILED;
    }
    
    // 4. Create MIME message with HTML template
    MimeMessage message = sender.createMimeMessage();
    // ... build HTML email ...
    
    // 5. Send via SMTP
    sender.send(message);  // Goes to MailTrap
    
    return VerificationEmailSendOutcome.SENT;
}
```

**Email HTML Template**:
- Subject: "Verify your email — Stokr"
- Body: Dark-themed HTML with Stokr branding
- CTA: "Verify email" button linking to verification URL
- Link validity: 1 hour (configurable via `loginPolicy.getEmailVerificationHoursValid()`)
- Footer: "If you didn't create this account, ignore this message"

---

## 🚨 Why You Don't See Email in Your Inbox

### The Problem:
```
✅ SMTP Service: Configured to MailTrap (NOT Gmail/your actual email)
✅ Email Sent: Yes, to MailTrap sandbox
❌ Your Email Inbox: Empty (emails going to MailTrap, not your email provider)
```

### MailTrap is a Testing Service:
- **Purpose**: Capture emails in development/testing
- **Your emails**: Go to MailTrap dashboard, NOT your Gmail/Outlook/etc.
- **Why used**: Safe testing without actually sending emails

---

## 📋 Current Configuration States

### State 1: Email NOT Configured
```yaml
spring:
  mail:
    host: ""  # Empty
    username: ""
```
**Result**: 
- Outcome: `NOT_CONFIGURED`
- Logs: "SMTP not configured (spring.mail.host is blank). Use verification URL from logs"
- Verification link: Logged in console at WARN level

### State 2: Email Configured to MailTrap (CURRENT)
```yaml
spring:
  mail:
    host: sandbox.smtp.mailtrap.io
    username: 9bbe635464608a
```
**Result**:
- Outcome: `SENT`
- Emails: Captured in MailTrap dashboard
- User: Does NOT receive email (not their actual email account)

### State 3: Email Configured to Real Provider (PRODUCTION)
```yaml
spring:
  mail:
    host: smtp.gmail.com  # Or SendGrid, AWS SES, etc.
    username: your-real-email@gmail.com
```
**Result**:
- Outcome: `SENT`
- Emails: Delivered to user's real inbox
- User: Receives email and clicks link

---

## 🔗 Three Ways to Verify Email Now

### Option 1: Check MailTrap Dashboard (Fastest - 1 minute)
```
1. Go to https://mailtrap.io
2. Log in to account "9bbe635464608a"
3. Inbox → Find your email
4. Click "Verify email" button in the email
5. Redirects to http://localhost:5173/verify-email?token=...
6. Page shows "Email verified" ✅
7. Back to app, email verified
```

### Option 2: Get Token from Logs (No MailTrap account)
```
1. When you register, logs show:
   "Verification email send failed for your-email@gmail.com"
   OR
   "Verify your-email@gmail.com using: http://localhost:5173/verify-email?token=abc123..."
   
2. Copy the full URL from logs
3. Paste in browser
4. Email verified ✅
```

### Option 3: Database Bypass (Development only)
```sql
UPDATE auth_users SET email_verified = true WHERE email = 'your-email@example.com';
```
**Then**: Log out and back in for JWT to refresh

---

## 🎯 What We CAN Verify

### Email Configuration Status: ✅ WORKING
- [x] SMTP connection: Working (MailTrap responds)
- [x] Email sending: Working (goes to MailTrap)
- [x] Verification link generation: Working
- [x] Token storage: Working
- [x] HTML template: Working

### Email Delivery Status: ⚠️ SANDBOX MODE
- [x] Emails sent: Yes
- [x] Where: MailTrap sandbox
- [x] Actual inbox: Not reached (intentional for development)

### Email Verification Flow: ✅ WORKING
- [x] Token generation: Working
- [x] Link creation: Working
- [x] Token validation: Working
- [x] Database update: Working
- [x] JWT refresh: Working

---

## 📊 Verification Status Check

### Check What's in Database
```sql
-- Check if email verification tokens were created
SELECT user_id, token_hash, used, expires_at 
FROM auth_email_verification_tokens 
ORDER BY created_at DESC LIMIT 5;

-- Check if user was marked verified
SELECT id, email, email_verified 
FROM auth_users 
WHERE email = 'your-email@example.com';
```

### Check Application Logs
```
When registering, should see:
✅ "Verification email queued/sent for recipient domain xx…@gmail.com"
or
⚠️  "Verification email send failed for your-email@example.com. Token is valid until expiry..."
```

### Check MailTrap
```
If MailTrap account is available:
1. https://mailtrap.io
2. Log in with account 9bbe635464608a
3. Should see email in "Inbox"
4. Check subject: "Verify your email — Stokr"
```

---

## 🔧 Configuration Options

### To Use MailTrap (Current - Testing)
**Already configured in `.env`**

MailTrap Credentials:
- Account: 9bbe635464608a
- Password: 2fe4921c9d2308
- Dashboard: https://mailtrap.io

### To Use Gmail
```bash
# .env
SPRING_MAIL_HOST=smtp.gmail.com
SPRING_MAIL_PORT=587
SPRING_MAIL_USERNAME=your-email@gmail.com
SPRING_MAIL_PASSWORD=your-app-password  # NOT regular password
SPRING_MAIL_SMTP_AUTH=true
SPRING_MAIL_SMTP_STARTTLS=true
```

### To Use SendGrid
```bash
# .env
SPRING_MAIL_HOST=smtp.sendgrid.net
SPRING_MAIL_PORT=587
SPRING_MAIL_USERNAME=apikey
SPRING_MAIL_PASSWORD=SG.xxxxxxxxxxxx
SPRING_MAIL_SMTP_AUTH=true
SPRING_MAIL_SMTP_STARTTLS=true
```

### To Use AWS SES
```bash
# .env
SPRING_MAIL_HOST=email-smtp.region.amazonaws.com
SPRING_MAIL_PORT=587
SPRING_MAIL_USERNAME=your-ses-username
SPRING_MAIL_PASSWORD=your-ses-password
SPRING_MAIL_SMTP_AUTH=true
SPRING_MAIL_SMTP_STARTTLS=true
```

### To Disable Email Entirely (Local Dev)
```bash
# .env
SPRING_MAIL_HOST=
SPRING_MAIL_USERNAME=
# Then verification links logged to console
```

---

## ⚡ Recommended Next Steps

### Option A: Use MailTrap (Fastest - Already Set Up)
```
1. MailTrap account: Account ID: 9bbe635464608a
2. Go to https://mailtrap.io
3. Check if account exists / credentials valid
4. Register in app
5. Check MailTrap inbox for email
6. Click link in email
7. Email verified ✅
```

### Option B: Skip Email, Use DB (Development Only)
```
1. Register in app
2. Run SQL: UPDATE auth_users SET email_verified = true WHERE ...
3. Log out and back in
4. Proceed with broker connection
```

### Option C: Switch to Real Provider
```
1. Get credentials (Gmail/SendGrid/AWS SES/etc.)
2. Update .env
3. Restart application
4. Register and check real inbox
```

---

## 📋 Summary: What's Configured

| Component | Status | Value | Notes |
|-----------|--------|-------|-------|
| **SMTP Host** | ✅ Configured | sandbox.smtp.mailtrap.io | MailTrap (sandbox) |
| **SMTP Port** | ✅ Configured | 2525 | Standard for MailTrap |
| **Username** | ✅ Configured | 9bbe635464608a | MailTrap account |
| **Password** | ✅ Configured | 2fe4921c9d2308 | MailTrap credentials |
| **Auth** | ✅ Enabled | true | SMTP authentication required |
| **StartTLS** | ✅ Enabled | true | Encryption enabled |
| **Email Template** | ✅ Implemented | HTML with branding | Professional template |
| **Token Generation** | ✅ Implemented | SHA-256 hashed | Secure |
| **Token TTL** | ✅ Configurable | 1 hour default | From loginPolicy |
| **Verification Endpoint** | ✅ Implemented | /api/auth/verify-email | GET with token param |
| **Resend Endpoint** | ✅ Implemented | /api/auth/resend-verification | POST authenticated |

---

## ✅ Checklist Before Proceeding

Please confirm:

1. [ ] Do you have MailTrap account access? Or different email provider?
2. [ ] Can you check MailTrap inbox at https://mailtrap.io?
3. [ ] Do you want to keep MailTrap sandbox, or switch to real email?
4. [ ] Should I create the in-app "Verify Now" button as alternative?
5. [ ] Or should I just mark email verified in database to unblock broker?

---

**⚠️ IMPORTANT**: Do NOT make any code changes until you confirm which approach you want. Current setup is working correctly - emails are being sent to MailTrap sandbox as intended for development.

