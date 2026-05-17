# NSE INTRADAY PLATFORM - COMPLETE TECHNICAL SPECIFICATION
## Production-Ready Blueprint with Screen Designs, Database Schemas, Calculations, and Business Logic

---

# TABLE OF CONTENTS
1. System Architecture Overview
2. Database Schema (Complete)
3. Screen-by-Screen Specifications
4. Real-Time Calculation Engines
5. Algorithm Explanations
6. API Endpoints (Complete)
7. User Flows & Business Logic
8. Analytics & Retention Mechanics
9. Mobile Responsiveness
10. Error Handling & Edge Cases

---

# SECTION 1: SYSTEM ARCHITECTURE OVERVIEW

## System Flow Diagram

```
NSE Live Feed (Tick Data)
    ↓
Data Ingestion Layer (Redis)
    ├─ Real-time prices (every tick)
    ├─ Bid/Ask updates
    └─ Volume data
    ↓
Calculation Engines (Every 1 minute)
    ├─ VWAP Calculator
    ├─ ATR Calculator
    ├─ Market Regime Detector
    ├─ Setup Detector (4 types)
    └─ Probability Adjuster
    ↓
Ranking Engine (Every 5 minutes)
    ├─ Quality Score Calculation
    ├─ Top 12 Selection
    └─ Personalization Engine (Per User)
    ↓
Frontend Distribution
    ├─ Web Dashboard
    ├─ Mobile App
    └─ Push Notifications
    ↓
User Interaction
    ├─ View Setups
    ├─ Execute Trade
    ├─ Track P&L
    └─ Close Position
    ↓
Trade Recording
    ├─ Store in Database
    ├─ Update User Statistics
    ├─ Recalculate Personal Win Rates
    └─ Adjust Future Recommendations
```

---

# SECTION 2: DATABASE SCHEMA (COMPLETE)

## 2.1 Core Tables

### Table: `nse_stocks`
```sql
CREATE TABLE nse_stocks (
    stock_id VARCHAR(10) PRIMARY KEY,           -- HDFCBANK, INFY, TCS, etc.
    stock_name VARCHAR(100) NOT NULL,
    sector VARCHAR(50) NOT NULL,                -- Banking, IT, Energy, Auto, etc.
    market_cap_millions INT,
    average_volume_daily INT,
    lot_size INT,
    price_precision DECIMAL(10,2),              -- Tick size (typically 0.05)
    prev_close DECIMAL(10,2),
    prev_high DECIMAL(10,2),
    prev_low DECIMAL(10,2),
    52week_high DECIMAL(10,2),
    52week_low DECIMAL(10,2),
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);
```

### Table: `live_price_data` (TimescaleDB - Time Series)
```sql
CREATE TABLE live_price_data (
    time TIMESTAMP NOT NULL,
    stock_id VARCHAR(10),
    price DECIMAL(10,2),
    bid DECIMAL(10,2),
    ask DECIMAL(10,2),
    volume INT,
    cumulative_volume INT,
    open_time_price DECIMAL(10,2),              -- For gap calculation
    PRIMARY KEY (time, stock_id)
) PARTITION BY TIME INTERVAL '1 day';
```

### Table: `candles_1min` (1-Minute OHLCV)
```sql
CREATE TABLE candles_1min (
    candle_id BIGSERIAL PRIMARY KEY,
    stock_id VARCHAR(10) NOT NULL,
    time_open TIMESTAMP NOT NULL,
    time_close TIMESTAMP NOT NULL,
    open_price DECIMAL(10,2),
    high_price DECIMAL(10,2),
    low_price DECIMAL(10,2),
    close_price DECIMAL(10,2),
    volume INT,
    vwap_calculated DECIMAL(10,2),
    atr_14 DECIMAL(10,2),
    rsi_14 DECIMAL(10,2),
    macd_value DECIMAL(10,4),
    created_at TIMESTAMP,
    UNIQUE(stock_id, time_close)
) PARTITION BY RANGE (EXTRACT(YEAR FROM time_close));
```

### Table: `candles_5min` (5-Minute OHLCV)
```sql
CREATE TABLE candles_5min (
    candle_id BIGSERIAL PRIMARY KEY,
    stock_id VARCHAR(10) NOT NULL,
    time_open TIMESTAMP NOT NULL,
    time_close TIMESTAMP NOT NULL,
    open_price DECIMAL(10,2),
    high_price DECIMAL(10,2),
    low_price DECIMAL(10,2),
    close_price DECIMAL(10,2),
    volume INT,
    vwap_calculated DECIMAL(10,2),
    created_at TIMESTAMP,
    UNIQUE(stock_id, time_close)
);
```

### Table: `historical_win_rates` (Backtested Data)
```sql
CREATE TABLE historical_win_rates (
    win_rate_id BIGSERIAL PRIMARY KEY,
    setup_type VARCHAR(50),                     -- gap_fill, vwap_bounce, sector_laggard, early_breakout
    gap_direction VARCHAR(10),                  -- UP, DOWN (for gap_fill)
    gap_size_min DECIMAL(5,2),                  -- 0.3, 0.5, 1.0, 2.0
    gap_size_max DECIMAL(5,2),
    market_regime VARCHAR(20),                  -- TRENDING_UP, TRENDING_DOWN, CHOPPY, VOLATILE, QUIET
    volume_ratio_min DECIMAL(5,2),              -- For VWAP bounces
    volume_ratio_max DECIMAL(5,2),
    touches_min INT,                            -- For VWAP bounces
    touches_max INT,
    hour_of_day INT,                            -- 9, 10, 11 (for time-based analysis)
    sector VARCHAR(50),                         -- For sector laggard analysis
    win_count INT,
    loss_count INT,
    win_rate DECIMAL(5,4),
    avg_win_percent DECIMAL(5,4),
    avg_loss_percent DECIMAL(5,4),
    sample_size INT,
    confidence_level VARCHAR(20),               -- HIGH (n>100), MEDIUM (n>50), LOW (n<50)
    last_updated TIMESTAMP,
    created_at TIMESTAMP,
    INDEX(setup_type, market_regime, gap_direction)
);
```

### Table: `users`
```sql
CREATE TABLE users (
    user_id BIGSERIAL PRIMARY KEY,
    username VARCHAR(50) UNIQUE NOT NULL,
    email VARCHAR(100) UNIQUE NOT NULL,
    password_hash VARCHAR(255),
    phone VARCHAR(15),
    kyc_status VARCHAR(20),                     -- VERIFIED, PENDING, REJECTED
    account_balance DECIMAL(12,2),
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    last_login TIMESTAMP,
    is_active BOOLEAN DEFAULT TRUE
);
```

### Table: `user_preferences`
```sql
CREATE TABLE user_preferences (
    pref_id BIGSERIAL PRIMARY KEY,
    user_id BIGINT REFERENCES users(user_id),
    alert_enabled BOOLEAN DEFAULT TRUE,
    push_notification_enabled BOOLEAN DEFAULT TRUE,
    email_alert_enabled BOOLEAN DEFAULT FALSE,
    alert_frequency VARCHAR(20),                -- ONLY_HIGH_PROB, ALL, MODERATE
    setup_type_preferences VARCHAR(255),        -- JSON: {"gap_fill": true, "vwap_bounce": true}
    alert_time_windows TEXT,                    -- JSON: {"gap_fill": [9, 10], "vwap_bounce": [10, 11]}
    min_win_probability_threshold DECIMAL(5,4), -- Only alert if >70%
    min_rr_ratio_threshold DECIMAL(5,2),        -- Only alert if >1.0x
    preferred_sectors TEXT,                     -- JSON array
    avoid_sectors TEXT,                         -- JSON array
    risk_per_trade_percent DECIMAL(5,2),        -- 1%, 2%, 3%
    max_daily_trades INT,                       -- Max 10 trades/day?
    updated_at TIMESTAMP
);
```

### Table: `user_trades`
```sql
CREATE TABLE user_trades (
    trade_id BIGSERIAL PRIMARY KEY,
    user_id BIGINT REFERENCES users(user_id),
    stock_id VARCHAR(10),
    setup_type VARCHAR(50),
    entry_time TIMESTAMP NOT NULL,
    entry_price DECIMAL(10,2) NOT NULL,
    quantity INT NOT NULL,
    target_price DECIMAL(10,2),
    stop_loss_price DECIMAL(10,2),
    exit_time TIMESTAMP,
    exit_price DECIMAL(10,2),
    status VARCHAR(20),                         -- OPEN, CLOSED, STOPPED_OUT
    profit_loss DECIMAL(12,2),
    profit_loss_percent DECIMAL(6,4),
    holding_time_minutes INT,
    result VARCHAR(10),                         -- WIN, LOSS, BREAK_EVEN
    market_regime_at_entry VARCHAR(20),
    created_at TIMESTAMP,
    INDEX(user_id, entry_time),
    INDEX(stock_id, entry_time)
);
```

### Table: `user_statistics`
```sql
CREATE TABLE user_statistics (
    stat_id BIGSERIAL PRIMARY KEY,
    user_id BIGINT REFERENCES users(user_id),
    
    -- Overall Stats
    total_trades INT DEFAULT 0,
    winning_trades INT DEFAULT 0,
    losing_trades INT DEFAULT 0,
    win_rate DECIMAL(5,4),
    avg_win_percent DECIMAL(6,4),
    avg_loss_percent DECIMAL(6,4),
    total_profit DECIMAL(12,2),
    
    -- By Setup Type (JSON structure)
    gap_fill_stats JSON,                        -- {trades: 25, wins: 19, win_rate: 0.76, avg_win: 0.8, avg_loss: 0.4}
    vwap_bounce_stats JSON,
    sector_laggard_stats JSON,
    early_breakout_stats JSON,
    
    -- By Time of Day (JSON structure)
    by_hour_stats JSON,                         -- {9: {trades: 5, wins: 4}, 10: {trades: 8, wins: 6}}
    
    -- By Sector (JSON structure)
    by_sector_stats JSON,                       -- {Banking: {trades: 12, wins: 9}, IT: {trades: 8, wins: 5}}
    
    -- Streaks
    current_winning_streak INT DEFAULT 0,
    current_losing_streak INT DEFAULT 0,
    best_winning_streak INT DEFAULT 0,
    best_losing_streak INT DEFAULT 0,
    
    -- Daily Stats
    today_trades INT DEFAULT 0,
    today_profit DECIMAL(12,2),
    today_win_rate DECIMAL(5,4),
    
    -- Monthly Stats
    month_trades INT DEFAULT 0,
    month_profit DECIMAL(12,2),
    month_win_rate DECIMAL(5,4),
    
    updated_at TIMESTAMP
);
```

### Table: `current_setups` (Real-Time Ranking Board Cache)
```sql
CREATE TABLE current_setups (
    setup_id BIGSERIAL PRIMARY KEY,
    time_detected TIMESTAMP NOT NULL,
    stock_id VARCHAR(10) NOT NULL,
    setup_type VARCHAR(50),
    
    -- Setup Details
    entry_price DECIMAL(10,2),
    target_price DECIMAL(10,2),
    stop_loss DECIMAL(10,2),
    risk_amount DECIMAL(10,2),
    reward_amount DECIMAL(10,2),
    risk_reward_ratio DECIMAL(5,2),
    
    -- Probabilities
    base_probability DECIMAL(5,4),
    market_regime VARCHAR(20),
    regime_adjustment DECIMAL(5,2),
    adjusted_probability DECIMAL(5,4),
    
    -- Additional Data
    confidence_level VARCHAR(20),
    expected_value DECIMAL(10,4),
    quality_score DECIMAL(5,2),
    time_to_expiry_minutes INT,
    
    -- Metadata
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP,
    expires_at TIMESTAMP,
    
    INDEX(time_detected DESC),
    INDEX(stock_id),
    INDEX(quality_score DESC)
);
```

### Table: `sector_tracking` (Real-Time Sector Momentum)
```sql
CREATE TABLE sector_tracking (
    sector_id BIGSERIAL PRIMARY KEY,
    sector_name VARCHAR(50),
    time_tracked TIMESTAMP,
    
    sector_open DECIMAL(10,2),
    sector_current DECIMAL(10,2),
    sector_high DECIMAL(10,2),
    sector_low DECIMAL(10,2),
    
    sector_return_percent DECIMAL(6,4),
    sector_momentum DECIMAL(6,4),
    sector_volatility DECIMAL(6,4),
    
    top_performer VARCHAR(10),
    worst_performer VARCHAR(10),
    
    stock_count INT,
    avg_stock_return DECIMAL(6,4),
    
    created_at TIMESTAMP,
    INDEX(time_tracked DESC)
);
```

### Table: `market_regime_log`
```sql
CREATE TABLE market_regime_log (
    regime_id BIGSERIAL PRIMARY KEY,
    time_detected TIMESTAMP NOT NULL,
    regime VARCHAR(20),                         -- TRENDING_UP, TRENDING_DOWN, CHOPPY, VOLATILE, QUIET
    nifty50_price DECIMAL(10,2),
    nifty50_change_percent DECIMAL(6,4),
    trend_score DECIMAL(5,4),
    momentum DECIMAL(6,4),
    volatility_ratio DECIMAL(5,4),
    volume_ratio DECIMAL(5,4),
    
    -- Best setup type for this regime
    best_setup_type VARCHAR(50),
    expected_best_setup_prob DECIMAL(5,4),
    
    created_at TIMESTAMP,
    INDEX(time_detected DESC)
);
```

---

## 2.2 Redis Cache Structure

```
Key Structure: All stored in Redis for millisecond access

REAL-TIME DATA (Updates every tick):
├─ price:{stock_id} → "1652.30"
├─ bid:{stock_id} → "1652.25"
├─ ask:{stock_id} → "1652.35"
├─ volume:{stock_id} → "45000"
├─ last_update:{stock_id} → "2024-01-15 10:30:45.123"
└─ cumulative_volume:{stock_id} → "1250000"

CALCULATED METRICS (Updates every 1 minute):
├─ vwap:{stock_id} → "1651.80"
├─ atr_14:{stock_id} → "2.45"
├─ rsi_14:{stock_id} → "65.20"
├─ intraday_high:{stock_id} → "1655.50"
├─ intraday_low:{stock_id} → "1650.20"
└─ candle_1min:{stock_id}:latest → JSON {open, high, low, close, volume}

MARKET STATE (Updates every 1 minute):
├─ market_regime → "TRENDING_UP"
├─ nifty50_price → "23450.75"
├─ nifty50_change → "+0.82"
└─ market_regime_details → JSON {regime, trend_score, momentum, volatility_ratio}

RANKING BOARD (Updates every 5 minutes):
├─ top_setups → JSON Array [
    {
        stock_id: "HDFCBANK",
        setup_type: "vwap_bounce",
        quality_score: 89,
        adjusted_probability: 0.78,
        entry: 1652.30,
        target: 1658.50,
        stop: 1650.00
    },
    ... 11 more
  ]

USER-SPECIFIC (Updates on change):
├─ user_positions:{user_id} → JSON Array [
    {
        trade_id: 12345,
        stock_id: "HDFCBANK",
        entry_price: 1652.30,
        quantity: 1,
        current_price: 1655.20,
        profit_loss: 2.90,
        status: "OPEN"
    }
  ]

ALERTS SENT (For spam prevention):
├─ alerts_sent:{user_id}:{setup_type}:{stock_id} → "true" (TTL: 300 seconds)

SESSION DATA:
├─ user_session:{session_id} → JSON {user_id, login_time, expires_at}
└─ user_preferences_cache:{user_id} → JSON {alert settings, preferences}
```

---

# SECTION 3: SCREEN SPECIFICATIONS (Complete UI/UX)

## 3.1 LOGIN SCREEN

### Screen Layout
```
┌─────────────────────────────────────┐
│                                     │
│         NSE INTRADAY PRO            │ (Logo)
│      Smart Trading Platform         │
│                                     │
├─────────────────────────────────────┤
│                                     │
│  Email or Username                  │
│  ┌─────────────────────────────────┐│
│  │ user@example.com                ││
│  └─────────────────────────────────┘│
│                                     │
│  Password                           │
│  ┌─────────────────────────────────┐│
│  │ ••••••••••••••                  ││
│  └─────────────────────────────────┘│
│                                     │
│  [ Remember Me ]  [ Forgot Password]│
│                                     │
│  ┌─────────────────────────────────┐│
│  │   LOGIN (Button - Blue)         ││
│  └─────────────────────────────────┘│
│                                     │
│  Don't have account? [SIGN UP]      │
│                                     │
└─────────────────────────────────────┘
```

### Form Validation Rules
```
Email:
- Pattern: /^[^\s@]+@[^\s@]+\.[^\s@]+$/
- Length: 5-100 characters
- Error: "Invalid email format"

Password:
- Length: Minimum 8 characters
- Must contain: 1 uppercase, 1 lowercase, 1 number, 1 special char
- Error: "Password must be 8+ chars with uppercase, lowercase, number, special char"

Remember Me:
- Sets cookie: login_token (expires in 30 days)
```

### API Endpoint
```
POST /api/v1/auth/login
Headers: Content-Type: application/json
Body: {
    "email": "user@example.com",
    "password": "SecurePass123!",
    "remember_me": false
}

Response Success (200):
{
    "success": true,
    "user_id": 12345,
    "session_token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "expires_in": 3600,
    "user": {
        "name": "John Doe",
        "email": "john@example.com",
        "kyc_status": "VERIFIED"
    }
}

Response Error (401):
{
    "success": false,
    "error": "Invalid credentials",
    "error_code": "AUTH_INVALID_CREDENTIALS"
}
```

---

## 3.2 MAIN DASHBOARD - TAB 1: "MARKET PULSE"

### Screen Layout (Desktop Version - 1920x1080)

```
┌─────────────────────────────────────────────────────────────────────────┐
│ ← Back  NSE INTRADAY PRO                              🔔 🔧 👤 (Hamburger)│ (Header - Dark Gray #2C3E50)
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  MARKET PULSE                                    Time: 10:30:45 AM    │ (Title + Current Time)
│                                                                         │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  ┌───────────────────────────────────────────────────────────────────┐ │
│  │ 📊 TODAY'S TRADING ENVIRONMENT                                   │ │
│  ├───────────────────────────────────────────────────────────────────┤ │
│  │                                                                  │ │
│  │  Market Regime:  🟢 TRENDING UP ↗️                              │ │
│  │  Volatility:     🟡 MEDIUM-HIGH (VIX: 18.3)                    │ │
│  │  Opening Bias:   🟢 BULLISH (Pre-market: +0.8%, Sentiment: +ve)│ │
│  │  Today's Range:  1.2% - 1.8% (Expected based on ATR + IV)      │ │
│  │                                                                  │ │
│  │  ⚠️ MACRO EVENT ALERT:                                         │ │
│  │  Fed Decision in 2 hours 30 minutes                            │ │
│  │  Expected volatility spike: +40%                               │ │
│  │  Recommendation: Widen stops by 25%, consider smaller sizes    │ │
│  │                                                                  │ │
│  │  🏆 BEST SETUP TODAY:                                          │ │
│  │  1️⃣  GAP FILL PLAYS        Win Probability: 79%              │ │
│  │  2️⃣  VWAP BOUNCES          Win Probability: 71%              │ │
│  │  3️⃣  SECTOR ROTATIONS      Win Probability: 63%              │ │
│  │                                                                  │ │
│  │  ❌ AVOID TODAY:                                               │ │
│  │  Oversold Bounces (Market trending up, reversals fail 41%)     │ │
│  │                                                                  │ │
│  └───────────────────────────────────────────────────────────────────┘ │
│                                                                         │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  📊 LIVE RANKING BOARD (Auto-Updates Every 5 Minutes)                 │
│                                                                         │
│  ┌─────────────────────────────────────────────────────────────────┐ │
│  │ RANK │ STOCK  │ SETUP      │ WIN% │ ENTRY  │TARGET │STOP  │TIME│ │
│  ├─────┼────────┼────────────┼──────┼────────┼───────┼──────┼────┤ │
│  │  1  │HDFCBK  │VWAP Bounce │ 78%  │1652.30 │1658.50│1650  │ 12'│ │
│  │  ⭐⭐⭐⭐⭐  │        │            │      │        │       │     │ │
│  │                                                                  │ │
│  │  2  │INFY    │Gap Fill(D) │ 81%  │1485.60 │1480.00│1487  │ 8' │ │
│  │  ⭐⭐⭐⭐   │        │            │      │        │       │     │ │
│  │                                                                  │ │
│  │  3  │RELIANCE│Early BO    │ 76%  │2895.40 │2910.00│2888  │ 18'│ │
│  │  ⭐⭐⭐⭐   │        │            │      │        │       │     │ │
│  │                                                                  │ │
│  │  4  │TCS     │Sector Lag  │ 72%  │4125.50 │4135.00│4118  │ 25'│ │
│  │  ⭐⭐⭐    │        │            │      │        │       │     │ │
│  │                                                                  │ │
│  │  5  │WIPRO   │Gap Fill(U) │ 79%  │625.80  │632.00 │622.50│ 11'│ │
│  │  ⭐⭐⭐⭐   │        │            │      │        │       │     │ │
│  │                                                                  │ │
│  │  6  │BAJAJ-I │VWAP Bounce│ 75%  │10250.0 │10285.0│10240 │ 15'│ │
│  │  ⭐⭐⭐    │        │            │      │        │       │     │ │
│  │                                                                  │ │
│  │  7  │MARUTI  │Early BO    │ 74%  │11845.0 │11920.0│11820 │ 22'│ │
│  │  ⭐⭐⭐    │        │            │      │        │       │     │ │
│  │                                                                  │ │
│  │  8  │SBIN    │Sector Lag  │ 70%  │600.50  │605.00 │595.00│ 30'│ │
│  │  ⭐⭐⭐    │        │            │      │        │       │     │ │
│  │                                                                  │ │
│  │  9  │ITC     │Gap Fill(D) │ 77%  │485.30  │482.00 │487.50│ 14'│ │
│  │  ⭐⭐⭐    │        │            │      │        │       │     │ │
│  │                                                                  │ │
│  │  10 │ICICIBNK│VWAP Bounce│ 73%  │1125.40 │1130.00│1122  │ 19'│ │
│  │  ⭐⭐⭐    │        │            │      │        │       │     │ │
│  │                                                                  │ │
│  │  11 │AXISBNK │Gap Fill(U) │ 75%  │1095.60 │1102.00│1092  │ 16'│ │
│  │  ⭐⭐⭐    │        │            │      │        │       │     │ │
│  │                                                                  │ │
│  │  12 │BHARTI  │Early BO    │ 71%  │1485.50 │1500.00│1475  │ 24'│ │
│  │  ⭐⭐⭐    │        │            │      │        │       │     │ │
│  │                                                                  │ │
│  └─────────────────────────────────────────────────────────────────┘ │
│                                                                         │
│  💡 Click any stock to view chart, execute setup, or see details      │
│                                                                         │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  📈 YOUR ACTIVE TRADES TODAY                                          │
│                                                                         │
│  ┌─────────────────────────────────────────────────────────────────┐ │
│  │ STOCK  │ ENTRY  │ CURRENT │ P&L   │ % GAIN │ TARGET  │ STATUS  │ │
│  ├────────┼────────┼─────────┼───────┼────────┼─────────┼─────────┤ │
│  │HDFCBK  │1652.30 │1655.20  │+2.90  │+0.18%  │1658.50  │ 🟢 WINNING│ │
│  │TSLA    │245.30  │244.80   │-0.50  │-0.20%  │247.80   │ ⏳ HOLDING│ │
│  │AAPL    │192.10  │193.90   │+1.80  │+0.94%  │193.90   │ ✅ TARGET │ │
│  │GOOGL   │168.30  │169.10   │+0.80  │+0.48%  │170.50   │ ⏳ HOLDING│ │
│  └─────────────────────────────────────────────────────────────────┘ │
│                                                                         │
│  TODAY'S RESULTS:                                                     │
│  Total P&L: +$3,500 | Win Rate: 75% (3W 1L) | Expected Value: +$280  │
│  Next Alert: 11:47 AM                                                │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### Mobile Version (375x812)
```
┌──────────────────────────┐
│ ☰  Market Pulse  🔔  🔧 │ (Header)
├──────────────────────────┤
│ 📊 TRADING ENVIRONMENT   │
│ Regime: 🟢 TRENDING UP   │
│ Vol: 🟡 MEDIUM-HIGH      │
│ Range: 1.2% - 1.8%      │
│                          │
│ ⚠️ Fed in 2h 30m         │
│ Expected vol +40%        │
│                          │
│ 🏆 Best Setup:           │
│ 1️⃣ GAP FILLS (79%)      │
│ 2️⃣ VWAP (71%)           │
│ 3️⃣ SECTOR (63%)         │
├──────────────────────────┤
│ 📊 RANKING BOARD         │
│ [Scroll Down]            │
│                          │
│ 1 HDFCBK  VWAP 78% ⭐⭐⭐⭐⭐│
│   Entry:1652.30          │
│   Target:1658.50         │
│   Stop:1650.00           │
│   Time: 12'              │
│   [TAP FOR DETAILS]      │
│                          │
│ 2 INFY Gap 81% ⭐⭐⭐⭐ │
│   [TAP FOR DETAILS]      │
│                          │
│ 3 REL Early 76% ⭐⭐⭐⭐│
│   [TAP FOR DETAILS]      │
│                          │
│ [Scroll for more]        │
├──────────────────────────┤
│ 📈 ACTIVE TRADES         │
│ HDFCBK: +$2.90 (+0.18%) │
│ Target: 1658.50          │
│ [Tap to manage]          │
│                          │
│ TSLA: -$0.50 (-0.20%)   │
│ [Tap to manage]          │
│                          │
│ Total P&L: +$3,500       │
│ Win Rate: 75%            │
└──────────────────────────┘
```

### Interactive Elements & Behavior

```
1. RANKING BOARD ROWS:
   - Click on any row: Navigate to Stock Detail Screen
   - Hover (desktop): Show tooltip with calculation breakdown
   - Color coding:
     * Green: Win probability >75%
     * Orange: Win probability 65-75%
     * Red: Win probability <65%
   - Quality stars (1-5):
     * 5 stars: Quality score 85-100
     * 4 stars: Quality score 70-85
     * 3 stars: Quality score 55-70

2. TIME COUNTDOWN:
   - Updates every 10 seconds
   - When time reaches 0:
     * Stock removed from list (setup expired)
     * Explanation: "Setup window closed"
   - Color change as time approaches 0:
     * Green (>5 min remaining)
     * Orange (2-5 min remaining)
     * Red (<2 min remaining)

3. "EXECUTE SETUP" BUTTON:
   - Appears when user clicks stock
   - Opens Order Entry Modal
   - Pre-fills: Entry price, Target, Stop Loss
   - User confirms quantity based on account size

4. AUTO-REFRESH:
   - Entire ranking board refreshes every 5 minutes
   - Position P&L updates every 1 minute
   - Market Regime updates every 1 minute
   - Small refresh indicator in top-right corner
```

### Data Refresh Logic
```python
# Frontend State Management (React)

const [marketPulse, setMarketPulse] = useState({
    regime: 'TRENDING_UP',
    volatility: 'MEDIUM-HIGH',
    vix: 18.3,
    expectedRange: '1.2% - 1.8%',
    bestSetups: [
        {rank: 1, type: 'gap_fill', probability: 0.79},
        {rank: 2, type: 'vwap_bounce', probability: 0.71},
        {rank: 3, type: 'sector_laggard', probability: 0.63}
    ]
});

const [rankingBoard, setRankingBoard] = useState([
    // 12 top setups
]);

const [activePositions, setActivePositions] = useState([
    // User's active trades
]);

// WebSocket Connection (Real-Time Updates)
const ws = new WebSocket('wss://api.nseintradaypro.com/live');

ws.onmessage = (event) => {
    const data = JSON.parse(event.data);
    
    if (data.type === 'PRICE_UPDATE') {
        // Update position P&L
        updatePositionPnL(data.stock_id, data.new_price);
    }
    
    if (data.type === 'SETUP_RANKING_REFRESH') {
        // Every 5 minutes
        setRankingBoard(data.top_setups);
    }
    
    if (data.type === 'MARKET_REGIME_CHANGE') {
        // When regime changes
        setMarketPulse({...marketPulse, regime: data.new_regime});
    }
    
    if (data.type === 'MACRO_ALERT') {
        // Important event happening
        showMacroAlert(data.event, data.impact);
    }
};

// Timer for countdown
useEffect(() => {
    const interval = setInterval(() => {
        setRankingBoard(prev => 
            prev.map(setup => ({
                ...setup,
                time_remaining_minutes: setup.time_remaining_minutes - (1/60)
            }))
        );
    }, 1000);
    
    return () => clearInterval(interval);
}, []);
```

---

## 3.3 STOCK DETAIL SCREEN (When Clicking Ranking Item)

### Layout
```
┌─────────────────────────────────────────────────────────────────────────┐
│ ← Back  HDFCBANK - VWAP BOUNCE                         🔔 🔧 👤         │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  LIVE CHART (TradingView Widget)                                       │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │                                                                 │   │
│  │  [5min Chart with Entry/Target/Stop Marked]                  │   │
│  │                                                                 │   │
│  │  (Green entry line, Green target line, Red stop line)        │   │
│  │                                                                 │   │
│  │  Current Price: 1655.20  (Updated every tick)                │   │
│  │  Volume: 2,350,000 shares  (Updated every tick)              │   │
│  │                                                                 │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                                                         │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  SETUP DETAILS & CALCULATIONS                                         │
│                                                                         │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │ Setup Type:        VWAP Bounce                               │   │
│  │ Confidence Level:  🟢 HIGH                                   │   │
│  │                                                               │   │
│  │ VWAP Level:        1651.80                                   │   │
│  │ Current Price:     1655.20 (+0.21% from VWAP)               │   │
│  │ Number of Touches: 3 times today                             │   │
│  │ Volume Confirmation: 1.35x average (Strong)                 │   │
│  │                                                               │   │
│  │ ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━           │   │
│  │                                                               │   │
│  │ ENTRY PRICE:       1652.30                                   │   │
│  │ TARGET PRICE:      1658.50  (Swing High)                     │   │
│  │ STOP LOSS:         1650.00  (Below VWAP)                    │   │
│  │                                                               │   │
│  │ Risk Amount:       1652.30 - 1650.00 = 2.30                │   │
│  │ Reward Amount:     1658.50 - 1652.30 = 6.20                │   │
│  │ Risk/Reward Ratio: 6.20 / 2.30 = 2.70x  (EXCELLENT)        │   │
│  │                                                               │   │
│  │ ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━           │   │
│  │                                                               │   │
│  │ BASE PROBABILITY:  73% (Historical VWAP bounces)             │   │
│  │ Market Regime:     CHOPPY (Best for VWAP)                    │   │
│  │ Regime Adjustment: 1.15x (Choppy markets = +15% boost)      │   │
│  │ Volume Bonus:      1.10x (Strong volume confirmation)       │   │
│  │                                                               │   │
│  │ ADJUSTED PROBABILITY: 73% × 1.15 × 1.10 = 92%  ✅           │   │
│  │                                                               │   │
│  │ ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━           │   │
│  │                                                               │   │
│  │ EXPECTED VALUE:    (0.92 × 6.20) - (0.08 × 2.30)             │   │
│  │                  = 5.704 - 0.184 = +5.52 per share           │   │
│  │                  = +$276 (assuming 50 share lot)             │   │
│  │                                                               │   │
│  │ ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━           │   │
│  │                                                               │   │
│  │ YOUR PERSONAL EDGE:                                         │   │
│  │ Your VWAP Bounce Win Rate: 75% (vs Platform 73%)            │   │
│  │ Your Best Time: 10-11 AM (You win 76%)                      │   │
│  │ Current Time: 10:30 AM ✅ (Perfect time for you)            │   │
│  │ Banking Sector Record: 77% (You excel in Banking)           │   │
│  │                                                               │   │
│  │ RECOMMENDATION: ✅ HIGHLY RECOMMENDED FOR YOU                │   │
│  │ This is your best setup type at your best time in a          │   │
│  │ sector where you have proven edge (77% win rate).            │   │
│  │                                                               │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                                                         │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  ORDER ENTRY                                                           │
│                                                                         │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │ Account Size:      ₹5,00,000                                 │   │
│  │ Risk per Trade:    2% (₹10,000)                              │   │
│  │                                                               │   │
│  │ Stop Loss Distance: 2.30 per share                            │   │
│  │ Position Size:     ₹10,000 / 2.30 = 4,347 shares            │   │
│  │                                                               │   │
│  │ ⚠️ NOTE: Your broker lot size is 1, so you can enter exact  │   │
│  │ Position: 4,347 shares (Adjust manually if needed)           │   │
│  │                                                               │   │
│  │ ┌──────────────────────────────────────────┐                 │   │
│  │ │ Quantity: [4347]           shares        │                 │   │
│  │ └──────────────────────────────────────────┘                 │   │
│  │                                                               │   │
│  │ ┌──────────────────────────────────────────┐                 │   │
│  │ │ [EXECUTE SETUP]  [SAVE TO WATCHLIST]    │                 │   │
│  │ └──────────────────────────────────────────┘                 │   │
│  │                                                               │   │
│  │ Note: This will open your broker's order entry screen       │   │
│  │ Platform does NOT execute trades on your behalf            │   │
│  │                                                               │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### Calculation Breakdown Table (What Happens When User Clicks "Expand Calculations")

```
╔═══════════════════════════════════════════════════════════════════════╗
║ DETAILED CALCULATION BREAKDOWN - VWAP BOUNCE SETUP                   ║
╚═══════════════════════════════════════════════════════════════════════╝

1. VWAP CALCULATION:
   Formula: VWAP = Σ(Typical Price × Volume) / Σ(Volume)
   
   Where: Typical Price = (High + Low + Close) / 3
   
   Last 80 candles (5-minute) calculation:
   ┌──────┬─────┬─────┬─────┬──────────┬──────────┬──────────────┐
   │Time  │High │Low  │Close│Vol (units)│Typ Price│(TP × Vol)    │
   ├──────┼─────┼─────┼─────┼──────────┼──────────┼──────────────┤
   │9:30  │1645 │1643 │1644 │50,000    │1644.00  │82,200,000    │
   │9:35  │1650 │1645 │1650 │65,000    │1648.33  │107,141,450   │
   │9:40  │1652 │1648 │1649 │72,000    │1649.67  │118,695,424   │
   │...   │...  │...  │...  │...       │...      │...           │
   │10:25 │1656 │1654 │1655 │85,000    │1655.00  │140,675,000   │
   └──────┴─────┴─────┴─────┴──────────┴──────────┴──────────────┘
   
   Sum of (TP × Vol) = 10,847,362,500
   Sum of Vol = 6,547,500 units
   
   VWAP = 10,847,362,500 / 6,547,500 = 1,657.89
   
   ⚠️ Note: This is calculated on real live data every minute

2. PRICE DISTANCE FROM VWAP:
   Current Price: 1,655.20
   VWAP: 1,657.89
   Distance: |1,655.20 - 1,657.89| = 2.69
   Distance %: (2.69 / 1,657.89) × 100 = 0.162%
   
   Status: CLOSE TO VWAP (within 0.20%, suitable for bounce detection)

3. SWING HIGH CALCULATION:
   Looking at last 20 candles for highest high:
   ┌──────┬──────┐
   │Time  │High  │
   ├──────┼──────┤
   │10:05 │1660  │ ← SWING HIGH (highest in lookback period)
   │10:00 │1658  │
   │9:55  │1659  │
   │...   │...   │
   └──────┴──────┘
   
   Swing High Target: 1,660.00

4. ATR (Average True Range) CALCULATION:
   Formula: ATR = Average of (True Range) over 14 periods
   
   True Range = MAX(
       High - Low,
       |High - Previous Close|,
       |Low - Previous Close|
   )
   
   Last 14 candles:
   ┌───────┬──────────┬─────────────┬─────────────┬──────────┐
   │Period │High-Low  │|H-PC|       │|L-PC|       │True Range│
   ├───────┼──────────┼─────────────┼─────────────┼──────────┤
   │1      │3.00      │4.20         │1.50         │4.20      │
   │2      │2.50      │2.30         │0.80         │2.50      │
   │3      │4.10      │3.90         │2.20         │4.10      │
   │...    │...       │...          │...          │...       │
   │14     │2.80      │2.50         │1.20         │2.80      │
   └───────┴──────────┴─────────────┴─────────────┴──────────┘
   
   Sum of True Ranges = 45.30
   ATR = 45.30 / 14 = 3.24

5. RISK/REWARD CALCULATION:
   Entry: 1,652.30 (Current price + entry premium)
   Target: 1,658.50 (Swing High)
   Stop Loss: 1,650.00 (Previous support / VWAP - buffer)
   
   Risk = Entry - Stop Loss = 1,652.30 - 1,650.00 = 2.30
   Reward = Target - Entry = 1,658.50 - 1,652.30 = 6.20
   
   R:R Ratio = Reward / Risk = 6.20 / 2.30 = 2.70x
   
   Classification: EXCELLENT (>1.5x is good, >2.0x is excellent)

6. PROBABILITY CALCULATION (Multi-Step):
   
   Step A: Base Probability (From Historical Database)
   ──────────────────────────────────────────────────
   Setup Type: VWAP Bounce
   Volume Touches: 3 (3+ touches is highest confidence)
   Volume Ratio: 1.35x (>1.2 is high volume)
   
   Historical lookup from 5-year NSE data:
   3+ touches + >1.2x volume + current price direction = 73% win rate
   
   Base Probability: 73%
   
   
   Step B: Market Regime Adjustment
   ─────────────────────────────────
   Current Market Regime: CHOPPY (Detected from real-time data)
   
   Regime Impact on VWAP Bounces:
   ┌──────────────────┬────────────┐
   │Regime            │Adjustment  │
   ├──────────────────┼────────────┤
   │TRENDING_UP       │1.05x (↑)   │
   │TRENDING_DOWN     │1.05x (↑)   │
   │CHOPPY            │1.15x (↑↑)  │ ← Current regime
   │VOLATILE          │0.95x (↓)   │
   │QUIET             │0.88x (↓)   │
   └──────────────────┴────────────┘
   
   Reason: VWAP bounces work best in choppy/sideways markets
   because mean reversion is dominant
   
   Adjusted Probability = 73% × 1.15 = 83.95%
   
   
   Step C: Volume Confirmation Bonus
   ──────────────────────────────────
   Current Volume: 2,350,000 shares
   Average Volume (50-candle): 1,740,000 shares
   Volume Ratio: 2,350,000 / 1,740,000 = 1.35x
   
   Volume Confirmation Impact:
   if volume_ratio > 1.2: +10% bonus
   if volume_ratio > 1.5: +15% bonus
   
   Current: 1.35x → +10% bonus
   
   Adjusted Probability = 83.95% × 1.10 = 92.35%
   
   
   Step D: Time-of-Day Adjustment (For Your Personal Edge)
   ────────────────────────────────────────────────────────
   Your personal statistics show:
   - Overall VWAP bounce win rate: 75%
   - At 10:00-11:00 AM: 76% win rate
   - Current time: 10:30 AM ✅
   
   Since you have personal edge in this time window:
   Boost: +1% (since you're trading in your best window)
   
   Final Probability = 92.35% × 1.01 = 93.27%
   
   **FINAL ADJUSTED PROBABILITY: 92%** (Rounded for display)

7. EXPECTED VALUE CALCULATION:
   Formula: EV = (Win % × Reward) - (Loss % × Risk)
   
   EV per share = (0.92 × 6.20) - (0.08 × 2.30)
                = 5.704 - 0.184
                = 5.52 per share
   
   If trading 50 shares: 5.52 × 50 = ₹276 expected profit
   If trading 100 shares: 5.52 × 100 = ₹552 expected profit
   
   Assumption: These are average numbers based on historical data
   Actual results will vary

8. CONFIDENCE LEVEL DETERMINATION:
   
   Score Calculation:
   ├─ Probability > 75%: +30 points (max)
   ├─ R:R Ratio > 2.0x: +25 points (max)
   ├─ Volume Confirmation > 1.2x: +20 points (max)
   ├─ Touches >= 3: +15 points (max)
   ├─ Regime Favorable: +10 points (max)
   └─ Total: 30+25+20+15+10 = 100 points → HIGH confidence
   
   Final: 🟢 HIGH CONFIDENCE
```

---

## 3.4 TAB 2: "SETUP PLAYBOOK"

### Screen Layout

```
┌─────────────────────────────────────────────────────────────────────────┐
│ ← Back  SETUP PLAYBOOK                                    🔔 🔧 👤      │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  SELECT A SETUP TO LEARN:                                             │
│                                                                         │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │  [SETUP 1] [SETUP 2] [SETUP 3] [SETUP 4]                     │   │
│  │  Gap Fills  VWAP     Sector    Early                          │   │
│  │             Bounce   Laggard   Breakout                        │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                                                         │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  SETUP #1: GAP FILL PLAYS                                             │
│                                                                         │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │ 📚 WHAT IS IT?                                                │   │
│  │ ─────────────────────────────────────────────────────────────   │   │
│  │ Stock gaps up/down at open → Price reverts to previous close │   │
│  │ during trading day (called "gap fill")                       │   │
│  │                                                               │   │
│  │ Historical Success Rate: 82% of gaps fill on same day        │   │
│  │ Best Conditions: Gap size 0.5%-2%, normal volume             │   │
│  │ Worst Conditions: Gaps >3% in strong trending markets        │   │
│  │                                                               │   │
│  │ ┌─────────────────────────────────────────────────────────┐ │   │
│  │ │  EXAMPLE: INFY Today                                  │ │   │
│  │ │  ─────────────────────────────────────────────────────  │ │   │
│  │ │  Previous Close: 1,480.00                            │ │   │
│  │ │  Today Open: 1,485.50 (Gap Up +0.37%)               │ │   │
│  │ │  Expected Fill: Back to 1,480.00                    │ │   │
│  │ │  Time Frame: Typically 1-3 hours                    │ │   │
│  │ │  Probability: 79% (based on gap size + sector)      │ │   │
│  │ └─────────────────────────────────────────────────────┘ │   │
│  │                                                               │   │
│  │ YOUR PERFORMANCE:                                            │   │
│  │ ─────────────────────────────────────────────────────────   │   │
│  │ Total Gap Fills Traded: 41 trades                           │   │
│  │ Wins: 31 | Losses: 10 | Win Rate: 76%                      │   │
│  │ Average Win: +0.8% | Average Loss: -0.5%                   │   │
│  │ Expected Value: +0.57% per trade                            │   │
│  │                                                               │   │
│  │ ⚠️ YOUR WEAKNESSES:                                         │   │
│  │ • Biotech sector: Only 62% win rate (avoid!)               │   │
│  │ • After 1:00 PM: Only 68% win rate (morning gaps work best) │   │
│  │ • Large gaps (>2%): Only 71% win rate (small gaps better)   │   │
│  │                                                               │   │
│  │ ✅ YOUR STRENGTHS:                                          │   │
│  │ • Banking sector: 79% win rate (specialize here)            │   │
│  │ • 9:30-10:30 AM: 81% win rate (your best time)             │   │
│  │ • Gaps 0.5-1.5%: 77% win rate (optimal size)               │   │
│  │                                                               │   │
│  │ 📊 DETAILED STATS TABLE:                                   │   │
│  │ ─────────────────────────────────────────────────────────   │   │
│  │                                                               │   │
│  │ Time Period | Trades | Wins | W% | Avg W | Avg L | EV    │   │
│  │ ────────────┼────────┼──────┼────┼───────┼───────┼─────   │   │
│  │ 9:30-10:00 │  12    │  10  │83% │+0.9%  │-0.4%  │+0.65% │   │
│  │ 10:00-11:00│  15    │  13  │87% │+0.85% │-0.4%  │+0.69% │   │
│  │ 11:00-12:00│   8    │   6  │75% │+0.8%  │-0.6%  │+0.48% │   │
│  │ 12:00-1:00 │   4    │   2  │50% │+0.7%  │-0.7%  │0.00%  │   │
│  │ 1:00-3:30  │   2    │   0  │0%  │N/A    │-0.5%  │-0.5%  │   │
│  │                                                               │   │
│  │ Gap Size   | Trades | Wins | W%                            │   │
│  │ ────────────┼────────┼──────┼────                           │   │
│  │ 0.3-0.5%  │   8    │   6  │75% │                           │   │
│  │ 0.5-1%    │  18    │  15  │83% │                           │   │
│  │ 1-1.5%    │  10    │   8  │80% │                           │   │
│  │ 1.5-2%    │   5    │   2  │40% │                           │   │
│  │                                                               │   │
│  │ Sector     | Trades | Wins | W%                            │   │
│  │ ────────────┼────────┼──────┼────                           │   │
│  │ Banking    │  15    │  12  │80% │ ← YOUR EDGE             │   │
│  │ IT         │  12    │   9  │75% │                           │   │
│  │ Auto       │   8    │   6  │75% │                           │   │
│  │ Pharma     │   4    │   2  │50% │                           │   │
│  │ Biotech    │   2    │   1  │50% │ ← AVOID                 │   │
│  │                                                               │   │
│  │ 💡 ACTIONABLE INSIGHTS:                                    │   │
│  │ ─────────────────────────────────────────────────────────   │   │
│  │ 1. Focus on 9:30-10:30 AM window (you win 85% avg)        │   │
│  │ 2. Only trade Banking & IT sectors (your proven edge)       │   │
│  │ 3. Skip gaps >1.5% (sample too small, low success)         │   │
│  │ 4. Stop trading after 1 PM (your win rate drops 50%)       │   │
│  │ 5. Avoid Biotech completely (consistent losers)             │   │
│  │                                                               │   │
│  │ 📈 TODAY'S GAP FILL CANDIDATES:                            │   │
│  │ ─────────────────────────────────────────────────────────   │   │
│  │ 1. INFY - Gap Down 0.37%, Banking Sector                   │   │
│  │    Your Expected Win Rate: 83% (9:40 AM, IT, optimal gap)  │   │
│  │    Platform Win Rate: 79%                                   │   │
│  │    Entry: 1,485.60 | Target: 1,480.00 | Stop: 1,487.00    │   │
│  │    Risk/Reward: 1.6x | Expected Value: +$134              │   │
│  │    [EXECUTE THIS SETUP]                                     │   │
│  │                                                               │   │
│  │ 2. TCS - Gap Up 0.52%, IT Sector                           │   │
│  │    Your Expected Win Rate: 81% (9:55 AM, good parameters)  │   │
│  │    Platform Win Rate: 78%                                   │   │
│  │    Entry: 4,180.20 | Target: 4,160.00 | Stop: 4,185.00    │   │
│  │    Risk/Reward: 1.5x | Expected Value: +$89               │   │
│  │    [EXECUTE THIS SETUP]                                     │   │
│  │                                                               │   │
│  │ 3. HCLTECH - Gap Up 0.68%, IT Sector                       │   │
│  │    ⚠️ Outside your best window (9:40 AM window closed)     │   │
│  │    Not recommended                                           │   │
│  │                                                               │   │
│  └─────────────────────────────────────────────────────────────┘   │
│                                                                         │
│  [SWITCH TO SETUP 2: VWAP BOUNCES]                                    │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### Similar Layouts for Other 3 Setups:

**SETUP #2: VWAP BOUNCES** (Same structure as above)
```
📚 WHAT IS IT?
Price touches VWAP (true average price paid) → bounces away with momentum
Historical Success: 71% when bounce confirmed with volume

YOUR PERFORMANCE:
Total VWAP Bounces Traded: 28 trades
Wins: 19 | Losses: 9 | Win Rate: 68% (vs platform 71%)
Average Win: +1.1% | Average Loss: -0.6%
Expected Value: +0.59% per trade

⚠️ YOUR WEAKNESSES:
• Morning trades (9-10 AM): Only 61% win rate
• Low volume confirmation: 55% win rate

✅ YOUR STRENGTHS:
• Afternoon trades (2-3:30 PM): 76% win rate ← SPECIALIZE HERE
• Strong volume (>1.2x): 76% win rate
• Banking sector: 79% win rate
• 3+ touches of VWAP: 75% win rate

TODAY'S CANDIDATES:
1. HDFCBANK - VWAP Bounce 3 touches
   Your Expected: 75% (afternoon, banking, strong vol)
   Platform: 73%
   [EXECUTE]

2. AXISBANK - VWAP Bounce 2 touches
   Your Expected: 72% (afternoon, banking)
   [EXECUTE]

3. ICICIBANK - Morning VWAP Bounce
   ⚠️ Not recommended (you only win 61% in morning)
```

**SETUP #3: SECTOR LAGGARD CATCHES**
```
📚 WHAT IS IT?
Sector rallying → ONE stock is worst performer → Catches up (mean reversion)
Historical Success: 73% when sector is strong leader

YOUR PERFORMANCE:
Total Sector Lags Traded: 19 trades
Wins: 11 | Losses: 8 | Win Rate: 61% (vs platform 73%)
Average Win: +1.2% | Average Loss: -0.8%
Expected Value: +0.35% per trade (YOUR LOWEST)

⚠️ CRITICAL NOTE: Your win rate is BELOW platform average
Recommendation: Only trade when R:R > 1.5x to compensate

✅ YOUR STRENGTHS:
• Banking sector: 72% win rate
• Large catch-up gaps (>1%): 68% win rate

TODAY'S CANDIDATES:
(Only 1 candidate with good R:R)

1. GOOGL - Tech Sector Laggard
   Sector avg return: +1.7%
   GOOGL return: -0.2% (lag of 1.9%)
   Your Expected: 62% (below average)
   R:R Ratio: 1.5x (MINIMUM threshold)
   [NOT HIGHLY RECOMMENDED]
```

**SETUP #4: EARLY BREAKOUTS (9:30-10:30 AM ONLY)**
```
📚 WHAT IS IT?
Stock breaks above previous day's high in first hour → Follows through
Historical Success: 68% continuation (only in first hour)
⚠️ CRITICAL: Only valid 9:30-10:30 AM. After that, invalidate.

YOUR PERFORMANCE:
Total Early Breakouts Traded: 33 trades
Wins: 23 | Losses: 10 | Win Rate: 70% (vs platform 68%)
Average Win: +1.5% | Average Loss: -0.5%
Expected Value: +0.92% per trade (YOUR HIGHEST EDGE!) ⭐

✅ YOUR STRENGTHS:
• This is your BEST setup type
• Consistent edge above platform average
• Highest average win size (+1.5%)
• Lowest average loss size (-0.5%)

💡 RECOMMENDATION:
Make 50% of your daily trades in this setup during 9:30-10:30 AM window

TODAY'S CANDIDATES:
(Time-sensitive - only 2-3 available each morning)

1. NVDA - Previous high $121.20, current $120.50
   Probability: 69% | R:R: 2.1x | Time left: 43 minutes
   Your Expected: 71% (your personal win rate on early BOs)
   [EXECUTE - YOUR BEST EDGE]

2. TSLA - Previous high $246.50, current $247.20
   Probability: 68% | R:R: 4.0x | Time left: 43 minutes
   Your Expected: 70%
   [EXECUTE - EXCELLENT R:R]

3. MARUTI - Forms at 10:15 AM
   ⚠️ Time left: 15 minutes
   Probability: 71% | R:R: 1.8x
   Risky - too close to window end (your win rate drops to 54% after 10:30)
   [NOT RECOMMENDED]
```

---

## 3.5 TAB 3: "LIVE TRADING COMMAND CENTER"

### Layout

```
┌─────────────────────────────────────────────────────────────────────────┐
│ ← Back  TRADING COMMAND CENTER                          🔔 🔧 👤      │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  📊 YOUR POSITIONS TODAY                                               │
│                                                                         │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │                                                                 │   │
│  │  POSITION #1: HDFCBANK (VWAP Bounce)                          │   │
│  │  ─────────────────────────────────────────────────────────────  │   │
│  │  Entry Time: 10:15 AM | Entry Price: ₹1,652.30               │   │
│  │  Quantity: 50 shares                                          │   │
│  │                                                                │   │
│  │  Current Price: ₹1,655.20                                    │   │
│  │  Profit/Loss: +₹143.50 | % Gain: +0.18%                     │   │
│  │                                                                │   │
│  │  Target Price: ₹1,658.50                                     │   │
│  │  Stop Loss: ₹1,650.00                                        │   │
│  │  Max Drawdown Risk: -₹115 (-0.07%)                           │   │
│  │                                                                │   │
│  │  ┌──────────────────────────────────────────────────┐         │   │
│  │  │ Price Progress: 1650 ───[█████████░░░] 1659      │         │   │
│  │  │ Position: 🟢 54% toward target                   │         │   │
│  │  └──────────────────────────────────────────────────┘         │   │
│  │                                                                │   │
│  │  💡 SMART EXIT SUGGESTION:                                   │   │
│  │  Your setup is moving in right direction but not at target   │   │
│  │  yet. VWAP bounces can extend further, trail your stop.      │   │
│  │  Current recommendation: HOLD with trailing stop             │   │
│  │                                                                │   │
│  │  If price reaches ₹1,656.50: Move stop to ₹1,654 (lock gain) │   │
│  │  If price reaches target ₹1,658.50: Close 30%, trail 70%     │   │
│  │                                                                │   │
│  │  [CLOSE POSITION] [MOVE STOP] [HOLD]                         │   │
│  │                                                                │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                                                         │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │                                                                 │   │
│  │  POSITION #2: TSLA (VWAP Bounce)                              │   │
│  │  ─────────────────────────────────────────────────────────────  │   │
│  │  Entry Time: 10:31 AM | Entry Price: ₹245.30                 │   │
│  │  Quantity: 45 shares                                          │   │
│  │                                                                │   │
│  │  Current Price: ₹244.90                                      │   │
│  │  Profit/Loss: -₹18 | % Gain: -0.16%                          │   │
│  │                                                                │   │
│  │  Target Price: ₹247.80                                       │   │
│  │  Stop Loss: ₹244.00                                          │   │
│  │  Max Drawdown Risk: -₹58.50 (-0.05%)                         │   │
│  │                                                                │   │
│  │  ┌──────────────────────────────────────────────────┐         │   │
│  │  │ Price Progress: 244 ───[██████░░░░░░░░░] 248     │         │   │
│  │  │ Position: 🟡 34% toward target, -0.16% loss      │         │   │
│  │  └──────────────────────────────────────────────────┘         │   │
│  │                                                                │   │
│  │  ⚠️ ALERT: Price Approaching Stop Loss                        │   │
│  │  Current: ₹244.90 | Stop: ₹244.00 | Distance: ₹0.90          │   │
│  │  If breaks below ₹244: Auto-stop triggered                    │   │
│  │                                                                │   │
│  │  💡 SMART EXIT SUGGESTION:                                   │   │
│  │  Setup still valid but losing momentum. Volume declining.     │   │
│  │  Current probability of hitting target: Dropped to 58%        │   │
│  │  (from original 71% due to loss of momentum)                  │   │
│  │                                                                │   │
│  │  Decision Point: HOLD or CLOSE?                              │   │
│  │  - If you HOLD: Risk ₹58.50 | Potential gain ₹112.50        │   │
│  │  - Expected value at current prob (58%): -₹0.50              │   │
│  │  Recommendation: CLOSE if drops further (lock small loss)     │   │
│  │                                                                │   │
│  │  [CLOSE POSITION] [WAIT & WATCH] [SET TRAILING STOP]         │   │
│  │                                                                │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                                                         │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │                                                                 │   │
│  │  POSITION #3: AAPL (Gap Fill)                                 │   │
│  │  ─────────────────────────────────────────────────────────────  │   │
│  │  Entry Time: 10:23 AM | Entry Price: ₹192.10                 │   │
│  │  Quantity: 60 shares                                          │   │
│  │                                                                │   │
│  │  Current Price: ₹193.90                                      │   │
│  │  Profit/Loss: +₹108 | % Gain: +0.94%                         │   │
│  │                                                                │   │
│  │  Target Price: ₹191.20 (Gap Fill Level)                      │   │
│  │  Stop Loss: ₹190.50                                          │   │
│  │  Max Drawdown Risk: -₹96 (-0.06%)                            │   │
│  │                                                                │   │
│  │  ┌──────────────────────────────────────────────────┐         │   │
│  │  │ Price Progress: 190.5 ──[██████████████░] 193.9 │         │   │
│  │  │ Position: 🟢 GAP FILL COMPLETE! (100% achieved)│         │   │
│  │  └──────────────────────────────────────────────────┘         │   │
│  │                                                                │   │
│  │  ✅ GAP FILL TARGET ACHIEVED!                                │   │
│  │  But price continues higher (momentum)                        │   │
│  │  Current price: ₹193.90 vs Target: ₹191.20 (UP ₹2.70)       │   │
│  │                                                                │   │
│  │  💡 SMART EXIT SUGGESTION:                                   │   │
│  │  Gap fill setup is DONE. You've achieved +0.94% profit.      │   │
│  │  Price is now beyond original target.                         │   │
│  │                                                                │   │
│  │  Decision: PARTIAL CLOSE                                      │   │
│  │  Recommendation: Close 50% here (lock ₹54 profit),           │   │
│  │  Trail 50% with stop at ₹191.50 to catch extension           │   │
│  │                                                                │   │
│  │  Why this works:                                              │   │
│  │  - Original setup is complete (gap fill achieved)             │   │
│  │  - But momentum > edge, extend with smaller position          │   │
│  │  - Lock in 50% gains, give 50% chance to run                 │   │
│  │                                                                │   │
│  │  [CLOSE 50%] [CLOSE ALL] [TRAIL STOP] [HOLD]                │   │
│  │                                                                │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                                                         │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  📊 TODAY'S PERFORMANCE SNAPSHOT                                       │
│                                                                         │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │ Total P&L: +₹233.50                                           │   │
│  │ Open Positions: 3                                              │   │
│  │ Closed Positions: 1                                            │   │
│  │ Win Rate Today: 75% (3 wins, 1 loss)                          │   │
│  │                                                                │   │
│  │ Trades Executed: 4                                             │   │
│  │ Time Invested: 45 minutes (1 minute per trade average)        │   │
│  │                                                                │   │
│  │ Average Win: +0.67% | Average Loss: -0.30%                   │   │
│  │ Expected Value: +0.285% per trade executed                    │   │
│  │                                                                │   │
│  │ Your 30-day average: +₹1,800/day                             │   │
│  │ Today's pace: +₹3,500/day (94% above average) 🔥              │   │
│  │                                                                │   │
│  │ Streaks:                                                       │   │
│  │ Current Winning Streak: 3 consecutive wins                    │   │
│  │ Best Streak This Month: 7 consecutive wins                    │   │
│  │                                                                │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                                                         │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  🔔 REAL-TIME ALERTS                                                  │
│                                                                         │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │                                                                 │   │
│  │  [10:47 AM] 🟡 ALERT: TSLA Position Approaching Stop Loss     │   │
│  │  Current Price: ₹244.90 | Stop: ₹244.00 | Risk: ₹0.90        │   │
│  │  Action: [DISMISS] [CLOSE] [MOVE STOP]                       │   │
│  │                                                                │   │
│  │  [10:42 AM] 🟢 NEW SETUP: META VWAP Bounce Forming            │   │
│  │  Your win rate on afternoon VWAP bounces: 76%                │   │
│  │  Entry: ₹516.20 | Target: ₹518.90 | Prob: 72%               │   │
│  │  This is your BEST setup type RIGHT NOW                       │   │
│  │  [VIEW] [EXECUTE] [SKIP]                                      │   │
│  │                                                                │   │
│  │  [10:30 AM] 📢 MACRO UPDATE: Early Breakout Window Closing    │   │
│  │  In 10 minutes, early breakout window ends (10:30 AM close)  │   │
│  │  Stop looking for new early breakout entries                  │   │
│  │  [OK]                                                          │   │
│  │                                                                │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 3.6 MOBILE VERSION - Command Center

```
┌──────────────────────────┐
│ ← Trading Center  🔔 🔧  │
├──────────────────────────┤
│ 📊 TODAY'S P&L           │
│                          │
│ Total: +₹233.50          │
│ Win Rate: 75% (3W 1L)   │
│ Positions: 3 open        │
│                          │
├──────────────────────────┤
│ 📈 POSITION #1           │
│ HDFCBANK                 │
│ Entry: ₹1,652.30         │
│ Current: ₹1,655.20       │
│ +₹143.50 (+0.18%)       │
│ Target: ₹1,658.50        │
│ Stop: ₹1,650.00          │
│                          │
│ [54% to target]          │
│                          │
│ 💡 HOLD with trailing    │
│ stop                     │
│                          │
│ [CLOSE] [MANAGE]         │
│                          │
├──────────────────────────┤
│ ⚠️ POSITION #2          │
│ TSLA (Near Stop)         │
│ Entry: ₹245.30           │
│ Current: ₹244.90         │
│ -₹18 (-0.16%)           │
│ Target: ₹247.80          │
│ Stop: ₹244.00 (NEAR!)   │
│                          │
│ ⚠️ Recommend CLOSE       │
│                          │
│ [CLOSE NOW] [HOLD]       │
│                          │
├──────────────────────────┤
│ ✅ POSITION #3          │
│ AAPL (Target Hit)        │
│ Entry: ₹192.10           │
│ Current: ₹193.90         │
│ +₹108 (+0.94%)          │
│                          │
│ Gap fill COMPLETE!       │
│ Price above target       │
│                          │
│ 💡 Close 50%, trail 50%  │
│                          │
│ [CLOSE 50%] [TRAIL]      │
│                          │
├──────────────────────────┤
│ 🔔 ALERTS                │
│ • TSLA stop alert        │
│ • META VWAP forming      │
│ • Early BO window closes │
│                          │
│ [View All]               │
└──────────────────────────┘
```

---

# SECTION 4: REAL-TIME CALCULATION ENGINES (Code-Level Detail)

## 4.1 VWAP Calculator (Every Tick)

```python
class VWAPCalculator:
    """
    Real-time VWAP calculation
    Runs on every new tick (100+ times per minute)
    """
    
    def calculate_vwap(stock_id: str, timestamp: datetime) -> float:
        """
        VWAP = Cumulative(Price × Volume) / Cumulative(Volume)
        
        Key insight: Use 5-minute candles for intraday (not every single tick)
        Reason: Smooths out noise, still real-time enough
        """
        
        # Get last 80 5-minute candles (= 6.5 hours of trading)
        candles = TimescaleDB.query(f"""
            SELECT 
                time_close,
                high_price,
                low_price,
                close_price,
                volume
            FROM candles_5min
            WHERE stock_id = '{stock_id}'
            AND time_close >= NOW() - INTERVAL '330 minutes'
            ORDER BY time_close ASC
        """)
        
        if not candles:
            return None
        
        cumulative_pv = 0  # Cumulative Price × Volume
        cumulative_vol = 0  # Cumulative Volume
        
        for candle in candles:
            # Typical Price = (High + Low + Close) / 3
            typical_price = (
                candle.high_price + 
                candle.low_price + 
                candle.close_price
            ) / 3
            
            # Cumulative calculation
            pv = typical_price * candle.volume
            cumulative_pv += pv
            cumulative_vol += candle.volume
        
        # Calculate VWAP
        if cumulative_vol == 0:
            return None
        
        vwap = cumulative_pv / cumulative_vol
        
        # Store in Redis for instant retrieval
        Redis.set(f'vwap:{stock_id}', str(vwap))
        Redis.set(f'vwap_updated:{stock_id}', timestamp.isoformat())
        
        # Store in database for historical analysis
        TimescaleDB.insert('vwap_calculations', {
            'stock_id': stock_id,
            'timestamp': timestamp,
            'vwap_value': vwap,
            'cumulative_volume': cumulative_vol
        })
        
        return vwap


class VWAPTouchDetector:
    """
    Detects when price touches VWAP and bounces (rejection)
    """
    
    def is_touching_vwap(stock_id: str, current_price: float, vwap: float, 
                        tolerance_percent: float = 0.08) -> bool:
        """
        Check if current price is WITHIN tolerance of VWAP
        Default tolerance: 0.08% (tight tolerance for accuracy)
        """
        
        difference = abs(current_price - vwap) / vwap * 100
        
        return difference <= tolerance_percent
    
    
    def count_vwap_touches_in_hour(stock_id: str) -> int:
        """
        Count how many times price touched VWAP in last hour
        
        Returns: Number of touches (0, 1, 2, 3+)
        """
        
        # Get last 60 1-minute candles
        candles = TimescaleDB.query(f"""
            SELECT close_price FROM candles_1min
            WHERE stock_id = '{stock_id}'
            AND time_close >= NOW() - INTERVAL '60 minutes'
            ORDER BY time_close DESC
        """)
        
        vwap = Redis.get(f'vwap:{stock_id}')
        
        touches = 0
        for candle in candles:
            if VWAPTouchDetector.is_touching_vwap(
                stock_id, 
                candle.close_price, 
                float(vwap)
            ):
                touches += 1
        
        return touches
    
    
    def detect_rejection(stock_id: str) -> bool:
        """
        REJECTION = Price touches VWAP + bounces away with volume
        
        Detection logic:
        1. Is price currently at VWAP?
        2. Are last 5 ticks showing MOVEMENT AWAY from VWAP?
        3. Is volume above average?
        """
        
        current_price = Redis.get(f'price:{stock_id}')
        vwap = float(Redis.get(f'vwap:{stock_id}'))
        
        # Check 1: Currently near VWAP?
        if not VWAPTouchDetector.is_touching_vwap(stock_id, current_price, vwap):
            return False
        
        # Check 2: Get last 10 ticks
        last_ticks = Redis.lrange(f'ticks:{stock_id}', -10, -1)
        
        # Check if price is moving AWAY from VWAP
        direction_changes = 0
        for i in range(len(last_ticks)-1):
            prev_distance = abs(float(last_ticks[i]) - vwap)
            curr_distance = abs(float(last_ticks[i+1]) - vwap)
            
            if prev_distance < curr_distance:  # Moving away
                direction_changes += 1
        
        if direction_changes < 5:  # Need at least 50% of ticks moving away
            return False
        
        # Check 3: Volume above average?
        current_volume = Redis.get(f'volume:{stock_id}')
        avg_volume = TimescaleDB.query_single(f"""
            SELECT AVG(volume) as avg_vol FROM candles_1min
            WHERE stock_id = '{stock_id}'
            AND time_close >= NOW() - INTERVAL '50 minutes'
        """).avg_vol
        
        volume_ratio = float(current_volume) / float(avg_volume)
        
        if volume_ratio < 0.9:
            return False  # Volume not confirming
        
        # All checks passed: REJECTION DETECTED
        return True
```

---

## 4.2 Market Regime Detector (Every Minute)

```python
class MarketRegimeDetector:
    """
    Determines current market regime (TRENDING_UP, CHOPPY, VOLATILE, etc.)
    Runs every 1 minute
    Impacts: All setup probabilities get adjusted based on regime
    """
    
    @staticmethod
    def detect_regime(timestamp: datetime) -> dict:
        """
        Returns: {
            'regime': 'TRENDING_UP',
            'trend_score': 0.72,
            'momentum': 0.82,
            'volatility_ratio': 1.15,
            'confidence': 'HIGH'
        }
        """
        
        # Get last 100 1-minute candles for Nifty 50 index
        nifty_candles = TimescaleDB.query("""
            SELECT * FROM candles_1min
            WHERE stock_id = 'NIFTY50'
            AND time_close >= NOW() - INTERVAL '100 minutes'
            ORDER BY time_close ASC
        """)
        
        # 1. TREND SCORE (0-1): Higher high/low count
        higher_highs = 0
        higher_lows = 0
        
        for i in range(1, len(nifty_candles)):
            if nifty_candles[i].high_price > nifty_candles[i-1].high_price:
                higher_highs += 1
            if nifty_candles[i].low_price > nifty_candles[i-1].low_price:
                higher_lows += 1
        
        trend_score = (higher_highs + higher_lows) / (len(nifty_candles) * 2)
        
        # 2. MOMENTUM: Percentage change over last 20 candles
        momentum = (
            (nifty_candles[-1].close_price - nifty_candles[-20].close_price) 
            / nifty_candles[-20].close_price * 100
        )
        
        # 3. VOLATILITY RATIO: Current ATR vs Average ATR
        atr_current = calculate_atr('NIFTY50', 14)
        atr_average = TimescaleDB.query_single("""
            SELECT AVG(atr_14) as avg_atr FROM candles_1min
            WHERE stock_id = 'NIFTY50'
            AND time_close >= NOW() - INTERVAL '250 minutes'
        """).avg_atr
        
        volatility_ratio = atr_current / atr_average
        
        # 4. VOLUME RATIO: Current vs Average
        current_volume = nifty_candles[-1].volume
        avg_volume = sum([c.volume for c in nifty_candles[-50:]]) / 50
        volume_ratio = current_volume / avg_volume
        
        # 5. DETERMINE REGIME
        if trend_score > 0.65 and momentum > 0.3 and volatility_ratio < 1.2:
            regime = "TRENDING_UP"
        elif trend_score < 0.35 and momentum < -0.3 and volatility_ratio < 1.2:
            regime = "TRENDING_DOWN"
        elif volatility_ratio > 1.35:
            regime = "VOLATILE"
        elif volume_ratio < 0.65:
            regime = "QUIET"
        else:
            regime = "CHOPPY"
        
        # 6. CONFIDENCE LEVEL
        if trend_score > 0.65 or trend_score < 0.35:
            confidence = "HIGH"
        elif volatility_ratio > 1.5 or volume_ratio < 0.5:
            confidence = "LOW"
        else:
            confidence = "MEDIUM"
        
        regime_data = {
            'regime': regime,
            'trend_score': round(trend_score, 4),
            'momentum': round(momentum, 4),
            'volatility_ratio': round(volatility_ratio, 4),
            'volume_ratio': round(volume_ratio, 4),
            'confidence': confidence,
            'timestamp': timestamp
        }
        
        # Cache in Redis
        Redis.set('market_regime', json.dumps(regime_data))
        
        # Log for historical analysis
        TimescaleDB.insert('market_regime_log', regime_data)
        
        return regime_data
```

---

## 4.3 Gap Fill Detector

```python
class GapFillDetector:
    """
    Detects gap fill setup opportunities
    Gap fill probability depends on:
    - Gap size
    - Gap direction
    - Current market regime
    - Time of day
    """
    
    @staticmethod
    def detect_gap(stock_id: str) -> dict or None:
        """
        Returns: {
            'has_gap': True,
            'gap_direction': 'UP',
            'gap_size_percent': 0.85,
            'gap_size_rupees': 1.40,
            'target_price': 1480.00,
            'entry_price': 1485.40,
            'base_probability': 0.79,
            'adjusted_probability': 0.91
        }
        """
        
        # Get previous day close
        prev_day_close = TimescaleDB.query_single(f"""
            SELECT close_price FROM candles_1min
            WHERE stock_id = '{stock_id}'
            AND date_trunc('day', time_close) = CURRENT_DATE - INTERVAL '1 day'
            ORDER BY time_close DESC
            LIMIT 1
        """).close_price
        
        # Get today's open
        today_open = Redis.get(f'price:{stock_id}')  # First trade price
        
        # Calculate gap
        gap_rupees = float(today_open) - float(prev_day_close)
        gap_percent = (gap_rupees / float(prev_day_close)) * 100
        
        # Filter: Gap too small (<0.3%) not worth trading
        if abs(gap_percent) < 0.3:
            return None
        
        gap_direction = "UP" if gap_percent > 0 else "DOWN"
        
        # Get base probability from historical database
        base_prob = TimescaleDB.query_single(f"""
            SELECT win_rate FROM historical_win_rates
            WHERE setup_type = 'gap_fill'
            AND gap_direction = '{gap_direction}'
            AND gap_size_min <= {abs(gap_percent)}
            AND gap_size_max >= {abs(gap_percent)}
            LIMIT 1
        """)
        
        if not base_prob:
            base_probability = 0.70  # Default if not found
        else:
            base_probability = base_prob.win_rate
        
        # Get market regime
        regime_data = json.loads(Redis.get('market_regime'))
        regime = regime_data['regime']
        
        # Regime adjustment
        regime_adjustments = {
            'TRENDING_UP': 1.15 if gap_direction == 'DOWN' else 0.75,
            'TRENDING_DOWN': 1.20 if gap_direction == 'UP' else 0.70,
            'CHOPPY': 1.05,
            'VOLATILE': 0.90,
            'QUIET': 0.80
        }
        
        regime_adjustment = regime_adjustments.get(regime, 1.0)
        adjusted_probability = min(1.0, base_probability * regime_adjustment)
        
        # Target is previous day close
        target_price = float(prev_day_close)
        entry_price = float(today_open)
        
        return {
            'has_gap': True,
            'gap_direction': gap_direction,
            'gap_size_percent': round(abs(gap_percent), 4),
            'gap_size_rupees': round(abs(gap_rupees), 2),
            'target_price': round(target_price, 2),
            'entry_price': round(entry_price, 2),
            'prev_close': round(float(prev_day_close), 2),
            'base_probability': round(base_probability, 4),
            'regime': regime,
            'regime_adjustment': round(regime_adjustment, 2),
            'adjusted_probability': round(adjusted_probability, 4)
        }
```

---

## 4.4 Probability Adjuster Engine

```python
class ProbabilityAdjuster:
    """
    Takes base probability and adjusts for:
    1. Market regime
    2. Volume confirmation
    3. Time of day
    4. User's personal stats
    """
    
    @staticmethod
    def adjust_probability(
        setup_type: str,
        base_probability: float,
        market_regime: str,
        volume_ratio: float = 1.0,
        hour_of_day: int = None,
        stock_id: str = None,
        user_id: int = None
    ) -> dict:
        """
        Returns: {
            'base_probability': 0.73,
            'regime_adjusted': 0.84,
            'volume_adjusted': 0.93,
            'time_adjusted': 0.95,
            'user_personal_adjusted': 0.92,
            'final_probability': 0.92,
            'confidence': 'HIGH'
        }
        """
        
        adjusted_prob = base_probability
        adjustments = {}
        
        # 1. Regime Adjustment
        regime_adjustments = {
            'gap_fill': {
                'TRENDING_UP': 1.10,
                'TRENDING_DOWN': 1.15,
                'CHOPPY': 1.02,
                'VOLATILE': 0.92,
                'QUIET': 0.85
            },
            'vwap_bounce': {
                'TRENDING_UP': 1.05,
                'TRENDING_DOWN': 1.05,
                'CHOPPY': 1.15,  # VWAP bounces work BEST in choppy
                'VOLATILE': 0.95,
                'QUIET': 0.88
            },
            'sector_laggard': {
                'TRENDING_UP': 1.10,
                'TRENDING_DOWN': 0.70,
                'CHOPPY': 1.05,
                'VOLATILE': 0.95,
                'QUIET': 0.85
            },
            'early_breakout': {
                'TRENDING_UP': 1.20,
                'TRENDING_DOWN': 0.85,
                'CHOPPY': 0.95,
                'VOLATILE': 1.20,  # Breakouts work BEST in volatile
                'QUIET': 0.80
            }
        }
        
        regime_adj = regime_adjustments.get(setup_type, {}).get(market_regime, 1.0)
        adjusted_prob *= regime_adj
        adjustments['regime'] = regime_adj
        
        # 2. Volume Adjustment
        if volume_ratio > 1.2:
            volume_adj = 1.10
        elif volume_ratio > 1.0:
            volume_adj = 1.05
        elif volume_ratio < 0.8:
            volume_adj = 0.92
        else:
            volume_adj = 1.0
        
        adjusted_prob *= volume_adj
        adjustments['volume'] = volume_adj
        
        # 3. Time-of-Day Adjustment
        if hour_of_day:
            time_adjustments = {
                'gap_fill': {9: 1.15, 10: 1.10, 11: 1.05, 12: 0.90, 13: 0.80, 14: 0.75, 15: 0.85},
                'vwap_bounce': {9: 0.95, 10: 1.05, 11: 1.10, 12: 1.08, 13: 1.12, 14: 1.15, 15: 1.10},
                'early_breakout': {9: 1.05, 10: 1.15, 11: 0.50, 12: 0.50, 13: 0.50, 14: 0.50, 15: 0.50},
                'sector_laggard': {9: 0.90, 10: 1.05, 11: 1.10, 12: 1.05, 13: 1.00, 14: 0.95, 15: 1.05}
            }
            
            time_adj = time_adjustments.get(setup_type, {}).get(hour_of_day, 1.0)
            adjusted_prob *= time_adj
            adjustments['time_of_day'] = time_adj
        
        # 4. User Personal Edge Adjustment (If user data provided)
        if user_id:
            user_stats = get_user_stats(user_id, setup_type)
            if user_stats and user_stats.get('personal_win_rate'):
                personal_wr = user_stats['personal_win_rate']
                platform_avg = adjusted_prob
                
                # If user is better than platform, give bonus
                if personal_wr > platform_avg:
                    boost = min((personal_wr - platform_avg), 0.05)  # Max 5% boost
                    adjusted_prob += boost
                    adjustments['personal_edge'] = boost
        
        # 5. Cap probability at 0.98 (never 100% certain)
        final_probability = min(0.98, adjusted_prob)
        
        # Determine confidence
        if final_probability > 0.75:
            confidence = 'HIGH'
        elif final_probability > 0.65:
            confidence = 'MEDIUM'
        else:
            confidence = 'LOW'
        
        return {
            'base_probability': round(base_probability, 4),
            'regime_adjusted': round(base_probability * adjustments['regime'], 4),
            'volume_adjusted': round(base_probability * adjustments['regime'] * adjustments['volume'], 4),
            'time_adjusted': round(base_probability * adjustments['regime'] * adjustments['volume'] * adjustments.get('time_of_day', 1.0), 4),
            'final_probability': round(final_probability, 4),
            'confidence': confidence,
            'adjustments_applied': adjustments
        }
```

---

# SECTION 5: API ENDPOINTS (Complete List with Examples)

## 5.1 Authentication Endpoints

```
POST /api/v1/auth/register
POST /api/v1/auth/login
POST /api/v1/auth/logout
POST /api/v1/auth/refresh-token
POST /api/v1/auth/forgot-password
POST /api/v1/auth/reset-password
```

## 5.2 Market Data Endpoints

```
GET /api/v1/market/regime
Returns: Current market regime (TRENDING_UP, CHOPPY, etc.)

GET /api/v1/market/top-setups
Returns: Top 12 setups ranked by quality score

GET /api/v1/market/setups?setup_type=gap_fill
Returns: All active gap fill setups today

GET /api/v1/stock/{stock_id}/details
Returns: Full stock details, VWAP, ATR, indicators

GET /api/v1/stock/{stock_id}/live
Returns: Real-time price, bid/ask, volume
WebSocket: wss://api.nseintraday.com/live/{stock_id}
```

## 5.3 Setup Detection Endpoints

```
GET /api/v1/setup/detect/{stock_id}
Returns: All 4 setup detections for a stock

POST /api/v1/setup/explain/{setup_id}
Returns: Detailed breakdown of setup calculations

GET /api/v1/setup/backtest?setup_type=gap_fill&params=...
Returns: Historical performance data for setup
```

## 5.4 User Trading Endpoints

```
POST /api/v1/trade/execute
Body: {
    "stock_id": "HDFCBANK",
    "setup_type": "vwap_bounce",
    "entry_price": 1652.30,
    "target_price": 1658.50,
    "stop_loss": 1650.00,
    "quantity": 50
}

POST /api/v1/trade/{trade_id}/close
Body: {"exit_price": 1656.00}

GET /api/v1/trade/positions
Returns: All active positions with live P&L

GET /api/v1/trade/history
Returns: All closed trades with results
```

## 5.5 User Statistics Endpoints

```
GET /api/v1/user/stats
Returns: Overall trading statistics

GET /api/v1/user/stats/by-setup
Returns: Win rate breakdown by setup type

GET /api/v1/user/stats/by-time
Returns: Win rate breakdown by hour of day

GET /api/v1/user/stats/by-sector
Returns: Win rate breakdown by sector

GET /api/v1/user/recommendations
Returns: Personalized setup recommendations based on user edge
```

---

# SECTION 6: DATABASE INDEXING STRATEGY (For Performance)

```sql
-- Critical Indexes for Real-Time Performance

CREATE INDEX idx_candles_stock_time ON candles_1min(stock_id, time_close DESC);
CREATE INDEX idx_candles_stock_recent ON candles_1min(stock_id, time_close DESC) 
    WHERE time_close > NOW() - INTERVAL '1 day';

CREATE INDEX idx_user_trades_user_time ON user_trades(user_id, entry_time DESC);
CREATE INDEX idx_user_trades_stock_result ON user_trades(stock_id, result);

CREATE INDEX idx_win_rates_setup_regime ON historical_win_rates(setup_type, market_regime, gap_direction);

CREATE INDEX idx_current_setups_quality ON current_setups(quality_score DESC);
CREATE INDEX idx_current_setups_expiry ON current_setups(expires_at);

CREATE INDEX idx_sector_tracking_time ON sector_tracking(time_tracked DESC);

-- For Redis
Redis key patterns optimized for O(1) lookups:
- price:{stock_id} → Instant price lookup
- top_setups → Pre-calculated ranking board
- market_regime → Current regime (updated every minute)
```

---

# SECTION 7: ERROR HANDLING & EDGE CASES

## Common Issues & Solutions

```python
1. INSUFFICIENT DATA FOR CALCULATION:
   Problem: VWAP calculator needs 80 candles but only 20 available (early morning)
   Solution: Use partial data, show "Warming up" message, confidence = LOW
   
   if len(candles) < 50:
        return {'status': 'WARMING_UP', 'confidence': 'LOW', 'vwap': partial_vwap}

2. DATA STALENESS:
   Problem: Price hasn't updated in 5 seconds (market closed or data feed broken)
   Solution: Flag alert, stop calculating probabilities, show "Data Offline" message
   
   last_update = Redis.get(f'last_update:{stock_id}')
   if (datetime.now() - last_update).seconds > 10:
       raise DataStalenessError("Price data is stale")

3. EXTREME PRICE MOVEMENTS:
   Problem: Stock moves 15% in 1 minute (circuit breaker hit or data error)
   Solution: Validate against circuit limits, flag if suspicious
   
   price_change = (new_price - prev_price) / prev_price * 100
   if abs(price_change) > 10:  # Circuit breaker typically 10-20%
       alert("Extreme move detected, validate data")

4. ZERO VOLUME:
   Problem: Setup detected but zero volume (illiquid stock)
   Solution: Filter out, don't show to user, mark as "Illiquid"
   
   if current_volume < min_liquidity_threshold:
       return None  # Don't suggest this setup

5. USER ACCESSES AT MARKET CLOSE:
   Problem: Market closed, no new data, but user tries to trade
   Solution: Show historical data, disable execute button, show message
   
   if not is_market_open():
       setup.tradeable = False
       setup.message = "Market closed. Next setup detection at 9:30 AM"

6. NETWORK LATENCY:
   Problem: WebSocket message arrives delayed, prices out of sync
   Solution: Add timestamps, validate before processing, show age
   
   message_age = datetime.now() - message.timestamp
   if message_age.seconds > 30:
       log("Skipping old message")
       return

7. CONCURRENT TRADES ON SAME SETUP:
   Problem: User executes same setup twice (double-clicked)
   Solution: Disable button after click, add loading state
   
   const [isExecuting, setIsExecuting] = useState(False)
   const handleExecute = async () => {
       if (isExecuting) return;
       setIsExecuting(True);
       await API.executeTrade(...);
       setIsExecuting(False);
   }
```

---

# SECTION 8: REAL-TIME DATA SYNC ARCHITECTURE

## WebSocket Message Types

```
1. PRICE_UPDATE
   {
       "type": "PRICE_UPDATE",
       "stock_id": "HDFCBANK",
       "price": 1652.50,
       "bid": 1652.25,
       "ask": 1652.75,
       "volume": 50000,
       "timestamp": "2024-01-15 10:30:45.123"
   }
   Frequency: Every tick (100+ per minute for active stocks)

2. SETUP_CHANGE
   {
       "type": "SETUP_CHANGE",
       "action": "NEW_SETUP",
       "setup_id": "setup_12345",
       "stock_id": "HDFCBANK",
       "setup_type": "vwap_bounce",
       "probability": 0.78,
       "quality_score": 89
   }
   Frequency: Every 5 minutes (when ranking board updates)

3. MARKET_REGIME_CHANGE
   {
       "type": "MARKET_REGIME_CHANGE",
       "old_regime": "CHOPPY",
       "new_regime": "TRENDING_UP",
       "confidence": "HIGH",
       "reason": "Higher highs detected, momentum positive"
   }
   Frequency: When regime actually changes (typically 2-4 times/day)

4. POSITION_UPDATE
   {
       "type": "POSITION_UPDATE",
       "trade_id": 12345,
       "stock_id": "HDFCBANK",
       "current_price": 1655.20,
       "profit_loss": 143.50,
       "pnl_percent": 0.0088,
       "status": "IN_PROFIT"
   }
   Frequency: Every tick

5. ALERT_NOTIFICATION
   {
       "type": "ALERT",
       "alert_type": "STOP_LOSS_NEAR",
       "trade_id": 12345,
       "stock_id": "TSLA",
       "message": "Price approaching stop loss",
       "action": "ACTION_REQUIRED"
   }
   Frequency: On specific events
```

---

# SECTION 9: MOBILE RESPONSIVENESS SPECIFICATIONS

## Breakpoints

```
Mobile Small: 320px - 374px
Mobile Regular: 375px - 599px
Tablet: 600px - 999px
Desktop: 1000px+

Responsive Behavior:
- Mobile: Full-width cards, single column, larger tap targets (48px min)
- Tablet: 2-column layout where applicable
- Desktop: Multi-column, detailed information, rich tooltips
```

## Touch Interactions

```
Swipe up/down: Scroll through positions
Swipe left: Close position (with confirmation)
Long press: Show detailed menu
Double tap: Toggle details expansion
Pinch: Zoom on chart
```

---

# SECTION 10: BUSINESS METRICS & ANALYTICS

## Retention Metrics to Track

```python
class RetentionAnalytics:
    """
    Track metrics that indicate app engagement & retention
    """
    
    # Daily Metrics
    daily_active_users = COUNT(DISTINCT users WHERE used_app TODAY)
    avg_session_length = AVG(session_duration FOR users TODAY)
    trades_per_user = COUNT(trades) / COUNT(DISTINCT users)
    win_rate_actual = COUNT(winning_trades) / COUNT(total_trades)
    
    # Weekly Metrics
    7_day_retention = COUNT(users_active_week_2) / COUNT(users_active_week_1)
    weekly_new_users = COUNT(users_created THIS WEEK)
    weekly_setup_accuracy = COMPARE(platform_predictions VS actual_outcomes)
    
    # Monthly Metrics
    monthly_churn = COUNT(inactive_users_this_month) / COUNT(active_last_month)
    monthly_profit_per_user = AVG(user_profit_this_month)
    feature_adoption = SUM(users_using_feature) / total_users
    
    # Accuracy Metrics
    setup_prediction_accuracy = COUNT(correct_predictions) / COUNT(all_predictions)
    probability_calibration = COMPARE(predicted_probability VS actual_win_rate)
    
    # Business Metrics
    daily_revenue = SUM(premium_subscriptions + ads)
    customer_acquisition_cost = total_marketing_spend / new_customers
    lifetime_value = avg_revenue_per_user * avg_retention_months
```

---

# FINAL IMPLEMENTATION CHECKLIST

```
✅ PHASE 1: FOUNDATION (Week 1-2)
□ NSE data feed connection (real-time OHLCV)
□ Database schema creation (PostgreSQL + TimescaleDB)
□ Redis setup for caching
□ Basic VWAP calculation (accuracy validation)
□ Gap fill detector (backtested accuracy)
□ Login/authentication system
□ Market Pulse screen (basic ranking board)
□ Deploy with 5-10 beta users

✅ PHASE 2: CORE SETUPS (Week 3-4)
□ All 4 setup detectors fully functional
□ Market regime detector (tested for accuracy)
□ Probability adjuster engine (validated against historical data)
□ Stock Detail screen with calculations
□ Top 12 ranking board live
□ WebSocket real-time updates
□ Deploy to 50 beta users

✅ PHASE 3: TRADING (Week 5-6)
□ Trade execution (record in database)
□ Live P&L calculation (real-time)
□ Smart exit recommendations
□ Trading Command Center screen
□ Position tracking with live updates
□ Deploy to 200 beta users

✅ PHASE 4: PERSONALIZATION (Week 7-8)
□ User statistics tracking (by setup, time, sector)
□ Personal win rate calculation
□ Personalized recommendations
□ Setup Playbook screen (with user stats)
□ Custom alerts (based on user edge)
□ Mobile app (iOS + Android)
□ Public launch

✅ ONGOING:
□ Monitor accuracy (expected_probability VS actual_outcomes)
□ Collect user feedback
□ A/B test different probability adjustments
□ Improve setup detectors based on real data
□ Add advanced features (backtesting, journal analysis)
□ Scale infrastructure as user base grows
```

---

## END OF SPECIFICATION DOCUMENT

This document provides:
✅ Complete database schema
✅ Screen mockups (desktop + mobile)
✅ Calculation logic (VWAP, probabilities, adjustments)
✅ API endpoints
✅ Error handling
✅ Real-time architecture
✅ Business metrics
✅ Implementation roadmap

**Ready for coding. Every detail specified.**
