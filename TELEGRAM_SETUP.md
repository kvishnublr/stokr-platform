# Telegram Bot Setup & Configuration Guide

## Overview

Stokr Platform uses Telegram for real-time trading notifications and operator alerts.

**Bot:** `@STOKR_SIGNAL_BOT`  
**URL:** `t.me/STOKR_SIGNAL_BOT`

---

## Configuration

### Environment Variables

Add these to your `.env` file (in project root):

```env
# Telegram Bot Configuration
STOKR_TELEGRAM_BOT_TOKEN=8691052981:AAHgLda7jRjarQNpx2pYCAHFdJQ1xAZ-t-o
STOKR_TELEGRAM_BOT_USERNAME=STOKR_SIGNAL_BOT
STOKR_TELEGRAM_WEBHOOK_SECRET=stokr_signal_bot_webhook_secret_key_2024_secure
STOKR_TELEGRAM_OPERATOR_CHAT_ID=8035979136
STOKR_TELEGRAM_DRY_RUN=false
```

### What Each Variable Does

| Variable | Value | Purpose |
|----------|-------|---------|
| `STOKR_TELEGRAM_BOT_TOKEN` | `8691052981:AAHgLda7jRjarQNpx2pYCAHFdJQ1xAZ-t-o` | API access token from BotFather |
| `STOKR_TELEGRAM_BOT_USERNAME` | `STOKR_SIGNAL_BOT` | Bot's @username (for webhooks) |
| `STOKR_TELEGRAM_WEBHOOK_SECRET` | Webhook secret string | Security token for webhook validation |
| `STOKR_TELEGRAM_OPERATOR_CHAT_ID` | `8035979136` | Your personal chat ID for system alerts |
| `STOKR_TELEGRAM_DRY_RUN` | `false` | Set to `true` to log without sending |

---

## Setup Steps

### 1. Create .env File (if not exists)

On your Contabo server:

```bash
cd /path/to/stokr-platform
cat > .env << 'EOF'
# Database
DB_HOST=postgres
DB_PORT=5432
DB_NAME=stokr_platform
DB_USER=postgres
DB_PASSWORD=root123

# API/UI Ports
API_PORT=8080
UI_PORT=3000

# Telegram Configuration
STOKR_TELEGRAM_BOT_TOKEN=8691052981:AAHgLda7jRjarQNpx2pYCAHFdJQ1xAZ-t-o
STOKR_TELEGRAM_BOT_USERNAME=STOKR_SIGNAL_BOT
STOKR_TELEGRAM_WEBHOOK_SECRET=stokr_signal_bot_webhook_secret_key_2024_secure
STOKR_TELEGRAM_OPERATOR_CHAT_ID=8035979136
STOKR_TELEGRAM_DRY_RUN=false

# Other configs...
JWT_SECRET=your_jwt_secret_here
EOF
```

### 2. Verify Configuration

```bash
# Check if .env is loaded (Docker)
docker compose config | grep -A 5 telegram

# Or check application logs
docker logs stokr-api | grep -i telegram
```

### 3. Test Bot Connection

Once deployed, test with:

```bash
# Test operator alert
curl -X POST http://localhost:8080/api/admin/telegram/test-alert \
  -H "Content-Type: application/json" \
  -d '{"alertType":"TEST","text":"Test notification from Stokr"}'
```

---

## Notification Types

### User-Level Notifications

Sent to individual trader's Telegram chat (if verified):

| Type | Template | Example |
|------|----------|---------|
| **Order Filled** | `ORDER_FILLED` | "✅ Order filled RELIANCE 100 qty @ 2850.25" |
| **Trading Blocked** | `TRADER_ELIGIBILITY_BLOCK` | "🛑 Trading blocked: Insufficient margin" |
| **Risk Rejected** | `RISK_REJECT` | "⛔ Risk: Position limit exceeded" |
| **Runtime Status** | `RUNTIME_STATE` | "⚙️ Strategy runtime: RUNNING (instance-123)" |

### Operator-Level Alerts

Sent to operator chat ID (system-wide):

| Event | Example |
|-------|---------|
| **Execution Error** | Critical errors in order execution |
| **System Alert** | Service health, deployment events |
| **Risk Threshold** | Portfolio risk threshold breached |
| **Audit Log** | Important system events |

---

## User Telegram Verification

### For Traders to Receive Notifications

1. **Open the bot:** `t.me/STOKR_SIGNAL_BOT`
2. **Click START** or send `/start`
3. **In Stokr app**, go to **Profile → Telegram Verification**
4. **Enter the verification code** from bot
5. ✅ Chat linked and ready for notifications

### Verify Connection

In the app:
```
Admin Panel → Users → Select User → Telegram Status
Should show: "✅ Verified - Chat ID: xxx"
```

---

## Database Tracking

All notifications are logged in `notification_delivery_records` table:

```sql
-- View all notifications sent
SELECT user_id, channel, template_key, status, delivered_at
FROM notification_delivery_records
WHERE channel = 'TELEGRAM'
ORDER BY delivered_at DESC
LIMIT 20;

-- Check failed deliveries
SELECT user_id, template_key, last_error
FROM notification_delivery_records
WHERE channel = 'TELEGRAM'
AND status = 'FAILED'
ORDER BY created_at DESC;
```

---

## Troubleshooting

### Bot Not Sending Messages

**Check 1: Is .env loaded?**
```bash
docker compose ps
docker logs stokr-api | grep -i "telegram.*configured"
```

**Check 2: Is user verified?**
```bash
# In database
SELECT telegram_chat_id, telegram_verified 
FROM auth_users 
WHERE email = 'user@example.com';
```

**Check 3: Test dry-run mode**
```bash
# Set in .env to see logs without sending
STOKR_TELEGRAM_DRY_RUN=true
```

### Invalid Token Error

**Error:** `Telegram bot token not configured`

**Solution:** Check bot token in .env
```bash
grep STOKR_TELEGRAM_BOT_TOKEN .env
# Should output: STOKR_TELEGRAM_BOT_TOKEN=8691052981:...
```

### Webhook Secret Validation Failed

**Error:** `Webhook signature invalid`

**Solution:** Ensure webhook secret matches in:
1. `.env` file: `STOKR_TELEGRAM_WEBHOOK_SECRET`
2. Telegram bot webhook URL (if using webhooks)

### Chat ID Not Found

**Error:** `User has no verified Telegram chat`

**Solution:** 
1. User must open bot: `t.me/STOKR_SIGNAL_BOT`
2. Send `/start` message
3. Complete verification in app

---

## Deployment

### On Contabo Server

1. **SSH to server:**
   ```bash
   ssh your_contabo_user@your_ip
   cd /path/to/stokr-platform
   ```

2. **Update .env with credentials:**
   ```bash
   # Edit .env file with your values
   nano .env
   # Add STOKR_TELEGRAM_* variables
   ```

3. **Restart API container:**
   ```bash
   ./health-check.sh restart
   # or
   docker compose up -d api
   ```

4. **Verify deployment:**
   ```bash
   docker logs stokr-api | grep -i telegram
   # Should see: "TelegramDeliveryService initialized"
   ```

---

## Security Notes

⚠️ **Bot Token is Sensitive:**
- Keep it in `.env` (not in git)
- Never share publicly
- Rotate if compromised
- Store securely on server

⚠️ **Webhook Secret:**
- Min 32 characters
- Change in production
- Used to validate incoming updates

⚠️ **Operator Chat ID:**
- Only you should have access
- Change if bot is compromised
- Don't share in logs

---

## Testing Checklist

- [ ] .env file created with all credentials
- [ ] Docker container restarted
- [ ] Bot is responding to `/start` command
- [ ] User can verify in app
- [ ] User receives test notification
- [ ] Operator receives system alerts
- [ ] Failed deliveries are logged
- [ ] Telegram status shows "Verified" in app

---

## Support & Monitoring

### View Telegram Logs

```bash
# Real-time logs
docker logs stokr-api -f | grep -i telegram

# Last 100 lines
docker logs stokr-api | grep -i telegram | tail -100
```

### Check Notification Status

```sql
-- Summary of today's notifications
SELECT 
  template_key,
  status,
  COUNT(*) as count
FROM notification_delivery_records
WHERE channel = 'TELEGRAM'
AND created_at > CURRENT_DATE
GROUP BY template_key, status;
```

### Bot Commands

Send to `@STOKR_SIGNAL_BOT`:

| Command | Purpose |
|---------|---------|
| `/start` | Initialize and get verification code |
| `/help` | Show available commands |
| `/status` | Check connection status |
| `/unlink` | Remove verification |

---

## Next Steps

1. ✅ Add credentials to `.env` on Contabo
2. ✅ Restart application
3. ✅ Open `t.me/STOKR_SIGNAL_BOT` and send `/start`
4. ✅ Complete verification in app
5. ✅ Test with a paper trade to receive notification
6. ✅ Monitor logs for any issues

---

**Configured by:** Claude  
**Bot:** `@STOKR_SIGNAL_BOT`  
**Created:** May 25, 2026

