# 📊 Today's Signal Analytics Query Guide

## Overview
This guide helps you query today's signal performance data - specifically signals with confidence > 70.

---

## Method 1: Direct Database Query (PostgreSQL)

### Using psql CLI:
```bash
# Connect to database
psql -h localhost -U postgres -d stokr_platform -f TODAY_SIGNAL_ANALYTICS.sql

# Or connect and run individual queries:
psql -h localhost -U postgres -d stokr_platform
```

### Using Docker (if running in container):
```bash
docker exec -it stokr-postgres psql -U postgres -d stokr_platform -f /path/to/TODAY_SIGNAL_ANALYTICS.sql
```

### Using DBeaver or pgAdmin:
1. Open DBeaver/pgAdmin
2. Copy queries from TODAY_SIGNAL_ANALYTICS.sql
3. Paste and execute in SQL editor

---

## Method 2: Direct API Query (Recommended)

### Create a Spring REST Endpoint:

Add this to your API controller:

```java
@RestController
@RequestMapping("/api/signals")
public class SignalAnalyticsController {

    @Autowired
    private SignalRepository signalRepository;

    @GetMapping("/today/above-confidence/{confidence}")
    public ResponseEntity<SignalAnalyticsSummary> getTodaySignalsAboveConfidence(
            @PathVariable Integer confidence) {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        
        List<StrategySignal> signals = signalRepository.findTodayAboveConfidence(
            today,
            confidence
        );
        
        SignalAnalyticsSummary summary = new SignalAnalyticsSummary();
        summary.setTotalSignals(signals.size());
        summary.setTargetHits((int) signals.stream().filter(s -> s.getHitTarget()).count());
        summary.setSlHits((int) signals.stream().filter(s -> s.getHitStoploss()).count());
        
        return ResponseEntity.ok(summary);
    }

    @GetMapping("/today/by-strategy/{confidence}")
    public ResponseEntity<List<StrategyPerformance>> getTodayByStrategy(
            @PathVariable Integer confidence) {
        // Returns performance breakdown by strategy
        return ResponseEntity.ok(...);
    }

    @GetMapping("/today/by-symbol/{confidence}")
    public ResponseEntity<List<SymbolPerformance>> getTodayBySymbol(
            @PathVariable Integer confidence) {
        // Returns performance breakdown by symbol
        return ResponseEntity.ok(...);
    }
}
```

### API Endpoints to Add:

```
GET  /api/signals/today/above-confidence/70
     └─ Returns summary: total, hits, SL hits, percentages

GET  /api/signals/today/by-strategy/70
     └─ Returns breakdown by strategy name

GET  /api/signals/today/by-symbol/70
     └─ Returns breakdown by symbol

GET  /api/signals/today/confidence-distribution
     └─ Returns histogram: 90-100, 80-89, 70-79, 60-69, <60

GET  /api/signals/today/top-performers/70
     └─ Returns signals that hit target (with PNL)

GET  /api/signals/today/bottom-performers/70
     └─ Returns signals that hit SL (with PNL)

GET  /api/signals/today/still-open/70
     └─ Returns signals still in flight (no outcome yet)
```

---

## Method 3: Repository Queries (Java)

Add these methods to `StrategySignalRepository`:

```java
@Repository
public interface StrategySignalRepository extends JpaRepository<StrategySignal, UUID> {

    @Query("""
        SELECT s FROM StrategySignal s
        WHERE DATE(s.createdAt AT TIME ZONE 'Asia/Kolkata') = CURRENT_DATE
        AND s.confidenceScore > :minConfidence
        AND s.deleted = FALSE
        AND s.backtestRunId IS NULL
        """)
    List<StrategySignal> findTodayAboveConfidence(
        @Param("minConfidence") BigDecimal minConfidence
    );

    @Query("""
        SELECT new map(
            s.strategyName as strategyName,
            COUNT(s) as count,
            SUM(CASE WHEN s.hitTarget THEN 1 ELSE 0 END) as targetHits,
            SUM(CASE WHEN s.hitStoploss THEN 1 ELSE 0 END) as slHits
        ) FROM StrategySignal s
        WHERE DATE(s.createdAt AT TIME ZONE 'Asia/Kolkata') = CURRENT_DATE
        AND s.confidenceScore > :minConfidence
        AND s.deleted = FALSE
        AND s.backtestRunId IS NULL
        GROUP BY s.strategyName
        ORDER BY COUNT(s) DESC
        """)
    List<Map<String, Object>> getTodayByStrategy(
        @Param("minConfidence") BigDecimal minConfidence
    );

    @Query("""
        SELECT new map(
            s.symbol as symbol,
            COUNT(s) as count,
            SUM(CASE WHEN s.hitTarget THEN 1 ELSE 0 END) as targetHits
        ) FROM StrategySignal s
        WHERE DATE(s.createdAt AT TIME ZONE 'Asia/Kolkata') = CURRENT_DATE
        AND s.confidenceScore > :minConfidence
        AND s.deleted = FALSE
        AND s.backtestRunId IS NULL
        GROUP BY s.symbol
        ORDER BY COUNT(s) DESC
        """)
    List<Map<String, Object>> getTodayBySymbol(
        @Param("minConfidence") BigDecimal minConfidence
    );
}
```

---

## Method 4: Dashboard Widget (UI)

Add a widget to show today's stats:

```javascript
// React component to fetch and display
const TodaySignalStats = () => {
  const [stats, setStats] = useState(null);

  useEffect(() => {
    fetch('/api/signals/today/above-confidence/70')
      .then(r => r.json())
      .then(data => setStats(data));
  }, []);

  if (!stats) return <div>Loading...</div>;

  return (
    <div className="signal-stats">
      <h3>Today's Signals (Confidence > 70)</h3>
      <div className="stats-grid">
        <div className="stat">
          <span className="label">Total Signals</span>
          <span className="value">{stats.totalSignals}</span>
        </div>
        <div className="stat">
          <span className="label">Target Hits</span>
          <span className="value success">{stats.targetHits}</span>
        </div>
        <div className="stat">
          <span className="label">SL Hits</span>
          <span className="value error">{stats.slHits}</span>
        </div>
        <div className="stat">
          <span className="label">Win Rate</span>
          <span className="value">
            {(stats.targetHits / stats.totalSignals * 100).toFixed(1)}%
          </span>
        </div>
      </div>
    </div>
  );
};
```

---

## Quick SQL Queries

### Just the summary:
```sql
SELECT
    COUNT(*) as total,
    SUM(CASE WHEN hit_target THEN 1 ELSE 0 END) as target_hits,
    SUM(CASE WHEN hit_stoploss THEN 1 ELSE 0 END) as sl_hits
FROM strategy_signals
WHERE DATE(created_at AT TIME ZONE 'Asia/Kolkata') = CURRENT_DATE
AND confidence_score > 70
AND deleted = FALSE
AND backtest_run_id IS NULL;
```

### By strategy:
```sql
SELECT
    strategy_name,
    COUNT(*) as count,
    SUM(CASE WHEN hit_target THEN 1 ELSE 0 END) as target_hits,
    ROUND(100.0 * SUM(CASE WHEN hit_target THEN 1 ELSE 0 END) / COUNT(*), 1) as hit_pct
FROM strategy_signals
WHERE DATE(created_at AT TIME ZONE 'Asia/Kolkata') = CURRENT_DATE
AND confidence_score > 70
AND deleted = FALSE
AND backtest_run_id IS NULL
GROUP BY strategy_name
ORDER BY COUNT(*) DESC;
```

### By symbol (top 10):
```sql
SELECT
    symbol,
    COUNT(*) as count,
    SUM(CASE WHEN hit_target THEN 1 ELSE 0 END) as target_hits,
    ROUND(100.0 * SUM(CASE WHEN hit_target THEN 1 ELSE 0 END) / COUNT(*), 1) as hit_pct
FROM strategy_signals
WHERE DATE(created_at AT TIME ZONE 'Asia/Kolkata') = CURRENT_DATE
AND confidence_score > 70
AND deleted = FALSE
AND backtest_run_id IS NULL
GROUP BY symbol
ORDER BY COUNT(*) DESC
LIMIT 10;
```

---

## Example Output

### Summary:
```
Total Signals (>70 confidence): 47
Hit Target: 28
Hit StopLoss: 12
Target Hit %: 59.57
SL Hit %: 25.53
Still Open: 7
```

### By Strategy:
```
Strategy Name          Count  Target  SL  Target %
─────────────────────────────────────────────────
GAP_FILL              15     10      2   66.7%
NSE_SPIKE_DETECTION   12     8       3   66.7%
VWAP_BOUNCE           11     6       4   54.5%
SECTOR_LAGGARD        9      4       3   44.4%
```

### By Symbol:
```
Symbol      Count  Target  SL  Target %
──────────────────────────────────────
SBIN        8      5       2   62.5%
HDFC        7      4       2   57.1%
INFY        6      4       1   66.7%
RELIANCE    5      3       1   60.0%
TCS         5      3       1   60.0%
```

---

## Recommendations

### 1. Add to Dashboard
```
Create a "Today's Performance" widget showing:
├─ Total signals with confidence > 70
├─ Target hit rate (%)
├─ SL hit rate (%)
├─ PNL summary
└─ By strategy breakdown
```

### 2. Daily Report
```
Email/Slack report at EOD with:
├─ Today's summary stats
├─ Top performing strategy
├─ Best/worst symbol
├─ Win rate trend
└─ Signals still in flight
```

### 3. Monitor by Confidence Bracket
```
Track separately:
├─ 90-100: Very High confidence
├─ 80-89: High confidence
├─ 70-79: Good confidence
└─ 60-69: Moderate confidence

See which bracket has best hit rate
```

### 4. Real-Time Dashboard
```
Auto-refresh every 5 minutes showing:
├─ Live signal count
├─ Hit targets so far
├─ Hit SL so far
├─ Win rate updating
└─ Alerts for high-confidence signals
```

---

## Database Schema

### Key Columns in strategy_signals:
- `confidence_score` - 0-100 score
- `created_at` - When signal was generated
- `hit_target` - BOOLEAN (target reached)
- `hit_stoploss` - BOOLEAN (SL hit)
- `realized_pnl` - PNL amount
- `strategy_name` - Strategy that generated
- `symbol` - Trading symbol
- `entry_price` - Entry level
- `target_price` - Target level
- `stop_price` - Stop loss level

---

## Performance Tips

1. **Index by Date**: Queries filtered by `created_at` will benefit from index
2. **Cache Results**: Cache 5-minute summaries to reduce DB load
3. **Batch Queries**: Pull all data once, analyze in-memory
4. **Time Zone**: Always use 'Asia/Kolkata' for consistency

---

## Next Steps

1. **Choose Method**: Pick Method 1-4 based on your needs
2. **Run Query**: Execute the query to get baseline
3. **Create Widget**: Add dashboard widget to display
4. **Set Up Alert**: Alert when confidence > 80 and above
5. **Track Trend**: Monitor hit rate over time

**This gives you complete visibility into signal performance!** 📊
