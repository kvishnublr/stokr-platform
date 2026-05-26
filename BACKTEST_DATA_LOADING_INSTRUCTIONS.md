# Historical Data Loading for Backtesting

## Overview

Background process to load 5 years of NSE historical OHLCV data (2019-2024) for backtesting.

**Status:** Ready to run  
**Data Range:** Configurable (default: 1,825 days = 5 years)  
**API Source:** Zerodha Kite API  
**Chunk Size:** 55 days per call (Zerodha API limit)  
**Rate Limiting:** 350ms between calls

---

## Components

### 1. BacktestHistoricalDataLoader
**Location:** `stokr-bootstrap/src/main/java/com/stokr/bootstrap/feed/zerodha/BacktestHistoricalDataLoader.java`

Core background service that:
- Loads historical candles from Zerodha API
- Processes data in 55-day chunks (respects API limits)
- Rate-limits to 350ms between API calls
- Saves directly to `marketdata_candles` table
- Tracks progress (symbols completed, failed, total candles)
- Reports progress via logging

### 2. BacktestDataController
**Location:** `stokr-admin/src/main/java/com/stokr/admin/web/BacktestDataController.java`

REST API endpoints:
- `POST /api/v1/admin/backtest-data/start-load` - Start loading
- `GET /api/v1/admin/backtest-data/progress` - Get detailed progress
- `GET /api/v1/admin/backtest-data/summary` - Get quick summary
- `GET /api/v1/admin/backtest-data/health` - Health check

### 3. Configuration
**Location:** `application.yml`

```yaml
stokr:
  backtest:
    historical-lookback-days: 1825  # 5 years
    chunk-days: 55                   # Zerodha API limit
    rate-limit-ms: 350               # Rate limiting
    enabled: true
    symbols: []                       # Empty = all available
```

---

## How to Start Data Loading

### Option 1: Via HTTP API (Recommended)

Once the application is running, trigger the background load:

```bash
# Start historical data load for all symbols
curl -X POST http://localhost:8080/api/v1/admin/backtest-data/start-load

# Start for specific symbols
curl -X POST "http://localhost:8080/api/v1/admin/backtest-data/start-load?symbols=INFY,TCS,WIPRO,RELIANCE"
```

### Option 2: Automatic on Startup

The service will automatically load when:
1. Application starts (if configured)
2. Zerodha broker session is active
3. Instrument registry is populated

### Option 3: Manual Trigger via Spring Boot

In any Spring component, inject and call:

```java
@Autowired
private BacktestHistoricalDataLoader historicalDataLoader;

// Start background load
historicalDataLoader.startBackgroundHistoricalLoad(List.of("INFY", "TCS"));

// Check progress
Map<String, Object> progress = historicalDataLoader.getProgress();
```

---

## Monitoring Progress

### Check Status via API

```bash
# Get detailed progress
curl http://localhost:8080/api/v1/admin/backtest-data/progress

# Get quick summary
curl http://localhost:8080/api/v1/admin/backtest-data/summary

# Health check
curl http://localhost:8080/api/v1/admin/backtest-data/health
```

### Monitor via Logs

The service logs progress:

```
loader.start lookback_days=1825
loader.config symbols=50 date_range=2019-01-02 to 2026-05-26
loader.chunk symbol=INFY period=2019-01-02 to 2019-02-26
loader.success symbol=INFY candles_loaded=5280
...
╔════════════════════════════════════════════════════════╗
║     HISTORICAL DATA LOAD COMPLETE                      ║
╠════════════════════════════════════════════════════════╣
║  Total Candles Loaded: {count}                         ║
║  Symbols Completed:    {count}                         ║
║  Failed Symbols:       {count}                         ║
║  Elapsed Time:         {time}                          ║
╚════════════════════════════════════════════════════════╝
```

---

## Expected Performance

### Time Estimation

- **Per Symbol:** ~2-3 minutes
- **50 Symbols:** 2-3 hours
- **100 Symbols:** 4-6 hours

### Data Volume

- **Per 1-minute candle:** ~100 bytes
- **Per symbol, 5 years:** ~1.3 MB (13,000 trading days × ~100 bytes)
- **50 symbols:** ~65 MB
- **100 symbols:** ~130 MB

### Network

- **Rate Limiting:** 350ms between API calls
- **Chunk Size:** 55 days per request
- **Total Requests:** ~33 per symbol (365/55 ≈ 6.6 months worth)

---

## Database Impact

**Table:** `marketdata_candles`

**Indexes Used:**
- `(symbol, timeframe, open_time, deleted)` - Query optimization
- `(open_time, symbol)- Time range queries

**Upsert Logic:**
- Prevents duplicates via unique constraint
- Updates high/low to use GREATEST/LEAST (never shrink)
- Preserves open price from first insert
- Updates close/volume to latest
- Increments version on conflict

---

## Verification

### Check Data Loaded

```sql
-- Count total candles by symbol
SELECT symbol, COUNT(*) as candle_count, 
       MIN(open_time) as earliest, 
       MAX(open_time) as latest
FROM marketdata_candles
WHERE timeframe = '1m' AND deleted = FALSE
GROUP BY symbol
ORDER BY candle_count DESC;

-- Verify 5-year coverage
SELECT symbol, COUNT(DISTINCT DATE(open_time)) as trading_days
FROM marketdata_candles
WHERE timeframe = '1m' AND deleted = FALSE
GROUP BY symbol
HAVING COUNT(DISTINCT DATE(open_time)) > 1000;
```

### Test Backtesting

Once data is loaded, test BacktestEngine:

```java
// Example: Backtest INFY
NseStock stock = nseStockService.findById("INFY");
LocalDate from = LocalDate.of(2019, 1, 2);
LocalDate to = LocalDate.of(2024, 12, 31);

List<BacktestEngine.HistoricalCandle> candles = 
    marketdataCandleRepository
        .findBySymbolAndTimeframeAndOpenTimeBetweenAndDeletedFalseOrderByOpenTimeAsc(
            "INFY", "1m",
            from.atStartOfDay(IST).toInstant(),
            to.atTime(23, 59).atZone(IST).toInstant()
        )
        .stream()
        .map(c -> new BacktestEngine.HistoricalCandle(
            c.getOpenTime().atZone(IST).toLocalDate(),
            c.getOpenPrice(), c.getHighPrice(), c.getLowPrice(), 
            c.getClosePrice(), c.getVwap(), c.getVolume(), c.getAtr14()
        ))
        .toList();

BacktestEngine.BacktestResult result = backtestEngine.runBacktest(candles, stock);
```

---

## Troubleshooting

### Load Not Starting

**Problem:** Background loader reports "registry_empty"  
**Solution:** Wait for WebSocket to populate instrument registry (60 seconds after app startup)

**Problem:** No access token found  
**Solution:** Ensure Zerodha broker session is authenticated in the platform

### API Rate Limiting

**Problem:** Getting "Rate limit exceeded" errors  
**Solution:** Increase `rate-limit-ms` in config (default 350ms is safe)

### Incomplete Data

**Problem:** Some symbols failed to load  
**Solution:** Check logs for specific symbols and retry via API

```bash
# Retry failed symbols
curl -X POST "http://localhost:8080/api/v1/admin/backtest-data/start-load?symbols=FAILED_SYMBOL1,FAILED_SYMBOL2"
```

### Database Locks

**Problem:** "Deadlock" or "lock timeout" errors  
**Solution:** Reduce chunk size or increase rate limit

```yaml
stokr:
  backtest:
    chunk-days: 30  # Reduce from 55
    rate-limit-ms: 500  # Increase from 350
```

---

## What Gets Loaded

**Time Range:** Configurable (default 5 years from today backwards)  
**Timeframe:** 1-minute OHLCV candles  
**Fields:**
- `symbol` - NSE ticker
- `timeframe` - "1m"
- `open_time` - Instant (UTC)
- `open_price`, `high_price`, `low_price`, `close_price` - BigDecimal
- `volume` - BigDecimal
- `created_at`, `updated_at` - Timestamp

**Trading Days Only:** Weekends and holidays are skipped  
**Missing Days:** Automatically detected and filled

---

## Next Steps

1. **Start Loading**
   ```bash
   curl -X POST http://localhost:8080/api/v1/admin/backtest-data/start-load
   ```

2. **Monitor Progress**
   ```bash
   # Check every 30 seconds
   watch -n 30 "curl -s http://localhost:8080/api/v1/admin/backtest-data/summary | jq"
   ```

3. **Verify Data**
   ```sql
   SELECT COUNT(*) as total_candles FROM marketdata_candles 
   WHERE timeframe = '1m' AND deleted = FALSE;
   ```

4. **Test Strategies**
   ```bash
   # Once loaded, run BacktestEngine tests
   mvn test -Dtest=BacktestEngineTest
   ```

---

## Files Modified/Created

```
CREATED: stokr-bootstrap/.../BacktestHistoricalDataLoader.java
CREATED: stokr-admin/.../BacktestDataController.java
CREATED: stokr-marketdata/.../HistoricalDataLoadConfig.java
CREATED: BACKTEST_DATA_LOADING_INSTRUCTIONS.md
```

---

## Configuration Reference

### application.yml

```yaml
stokr:
  backfill:
    enabled: true
    lookback-days: 365  # Daily backfill (incremental)
  
  backtest:
    enabled: true
    historical-lookback-days: 1825  # 5-year backtest data
    chunk-days: 55
    rate-limit-ms: 350
    symbols: []  # Empty = all; or ["INFY", "TCS"]

zerodha:
  api-key: ${ZERODHA_API_KEY}
  access-token: ${ZERODHA_ACCESS_TOKEN}
```

---

## Summary

✅ **Ready to Load:** BacktestHistoricalDataLoader is production-ready  
✅ **One-Time Process:** Loads 5 years of data once, incremental updates daily  
✅ **Background Execution:** Non-blocking, progress tracked  
✅ **API Monitored:** REST endpoints for progress and health  
✅ **Database Optimized:** Efficient upsert logic, proper indexing  

**Start loading whenever ready. Will notify completion with full statistics.**
