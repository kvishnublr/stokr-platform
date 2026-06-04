# 🎯 CONFIDENCE-BASED DYNAMIC STRATEGY SYSTEM
**Phase**: Week 2 (Post-Order Flow Implementation)  
**Goal**: Enable traders to select confidence threshold and auto-generate signals

---

## 📋 SYSTEM ARCHITECTURE

```
┌─────────────────────────────────────────────────────────────┐
│         ORDER BOOK DATA (Real-Time, Per Symbol)             │
│    (From OrderFlowCollectorService - Phase 1)               │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│    CONFIDENCE CALCULATOR (Every 1 Minute)                   │
│    ├─ Get latest order flow snapshot (all Nifty 100)       │
│    ├─ Calculate intelligence score (0-100)                 │
│    └─ Store in confidence_scores table                     │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│    SIGNAL GENERATOR (Dynamic Strategy)                      │
│    ├─ Config: Trader selects threshold (60/70/80/90)       │
│    ├─ Rule: Generate signal if confidence > threshold      │
│    └─ Store in strategy_signals table                      │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│    DASHBOARD                                                │
│    ├─ Signals at 60 confidence: X generated                │
│    ├─ Signals at 70 confidence: Y generated                │
│    ├─ Signals at 80 confidence: Z generated                │
│    └─ Signals at 90 confidence: W generated                │
└─────────────────────────────────────────────────────────────┘
```

---

## 🔧 IMPLEMENTATION COMPONENTS

### 1. **Database Schema**

```sql
-- New tables to create:

-- 1A. Store minute-by-minute confidence scores
CREATE TABLE confidence_scores (
    id SERIAL PRIMARY KEY,
    symbol VARCHAR(20) NOT NULL,
    timestamp TIMESTAMPTZ NOT NULL,
    confidence_score INTEGER CHECK (confidence >= 0 AND confidence <= 100),
    buyer_pressure INTEGER,
    seller_pressure INTEGER,
    liquidity_score INTEGER,
    signal_strength VARCHAR(30),
    created_at TIMESTAMPTZ DEFAULT NOW()
);
-- Indexes: (symbol, timestamp), (timestamp), (confidence_score)

-- 1B. Trader configuration for thresholds
CREATE TABLE confidence_strategy_config (
    id SERIAL PRIMARY KEY,
    trader_id UUID NOT NULL,
    strategy_name VARCHAR(128) NOT NULL,
    min_confidence_threshold INTEGER (60, 70, 80, or 90),
    enabled BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);
-- Indexes: (trader_id), (strategy_name)

-- 1C. Summary table (updated every hour)
CREATE TABLE confidence_signal_summary (
    id SERIAL PRIMARY KEY,
    date DATE NOT NULL,
    threshold_60_count INTEGER,
    threshold_70_count INTEGER,
    threshold_80_count INTEGER,
    threshold_90_count INTEGER,
    threshold_60_hits INTEGER,
    threshold_70_hits INTEGER,
    threshold_80_hits INTEGER,
    threshold_90_hits INTEGER,
    created_at TIMESTAMPTZ DEFAULT NOW()
);
```

### 2. **Java Service: Confidence Calculator**

```java
@Service
@Slf4j
public class ConfidenceScoreCalculatorService {

    @Autowired
    private OrderFlowMetricsService metricsService;
    
    @Autowired
    private ConfidenceScoreRepository scoreRepository;

    // Run every 1 minute
    @Scheduled(fixedRate = 60000)
    public void calculateConfidenceForAllNifty100() {
        log.info("🔄 Starting confidence calculation for Nifty 100...");
        
        List<String> nifty100Symbols = getNifty100Symbols();
        
        for (String symbol : nifty100Symbols) {
            try {
                OrderFlowSignalEnhancement signal = 
                    metricsService.getOrderFlowSignal(symbol);
                
                if (signal != null && !signal.getError()) {
                    ConfidenceScore score = new ConfidenceScore();
                    score.setSymbol(symbol);
                    score.setTimestamp(Instant.now());
                    score.setConfidenceScore(signal.getConfidence());
                    score.setBuyerPressure(signal.getBuyerPressureScore());
                    score.setSellerPressure(signal.getSellerPressureScore());
                    score.setLiquidityScore(signal.getLiquidityScore());
                    score.setSignalStrength(signal.getSignalStrength());
                    
                    scoreRepository.save(score);
                }
            } catch (Exception e) {
                log.warn("Failed to calculate confidence for {}", symbol, e);
            }
        }
        
        log.info("✅ Confidence calculation complete for {} symbols", 
                 nifty100Symbols.size());
    }

    private List<String> getNifty100Symbols() {
        // Get from universe configuration
        return Arrays.asList(
            "SBIN", "HDFC", "INFY", "RELIANCE", "TCS",
            // ... all 100 symbols
        );
    }
}
```

### 3. **Java Service: Dynamic Signal Generator**

```java
@Service
@Slf4j
public class ConfidenceBasedSignalGeneratorService {

    @Autowired
    private ConfidenceScoreRepository scoreRepository;
    
    @Autowired
    private StrategySignalRepository signalRepository;
    
    @Autowired
    private ConfidenceStrategyConfigRepository configRepository;

    // Run every 2 minutes (after confidence calculation)
    @Scheduled(fixedRate = 120000, initialDelay = 70000)
    public void generateSignalsBasedOnConfidence() {
        log.info("🎯 Starting signal generation from confidence scores...");
        
        List<ConfidenceStrategyConfig> configs = 
            configRepository.findByEnabledTrue();
        
        for (ConfidenceStrategyConfig config : configs) {
            generateSignalsForConfig(config);
        }
    }

    private void generateSignalsForConfig(ConfidenceStrategyConfig config) {
        int threshold = config.getMinConfidenceThreshold();
        
        // Get all symbols with confidence >= threshold in last minute
        List<ConfidenceScore> highConfidenceScores = 
            scoreRepository.findRecentByConfidenceThreshold(threshold);
        
        log.info("Found {} symbols with confidence > {} for trader {}",
                 highConfidenceScores.size(), threshold, 
                 config.getTraderId());
        
        for (ConfidenceScore score : highConfidenceScores) {
            // Check if signal already exists for this symbol/timestamp
            if (!signalAlreadyExists(score)) {
                createSignal(score, config);
            }
        }
    }

    private void createSignal(ConfidenceScore score, 
                             ConfidenceStrategyConfig config) {
        StrategySignal signal = new StrategySignal();
        signal.setStrategyName("CONFIDENCE_BASED_" + 
                              config.getMinConfidenceThreshold());
        signal.setSymbol(score.getSymbol());
        signal.setConfidenceScore(BigDecimal.valueOf(score.getConfidenceScore()));
        signal.setUserId(config.getTraderId());
        signal.setCreatedAt(Instant.now());
        signal.setReason("Confidence score: " + score.getConfidenceScore());
        
        // TODO: Calculate entry, target, SL based on price + score
        
        signalRepository.save(signal);
        log.debug("✅ Generated signal for {} at confidence {}", 
                 score.getSymbol(), score.getConfidenceScore());
    }

    private boolean signalAlreadyExists(ConfidenceScore score) {
        return signalRepository.existsBySymbolAndCreatedAtAfter(
            score.getSymbol(),
            Instant.now().minusSeconds(120)  // Within last 2 minutes
        );
    }
}
```

### 4. **REST Endpoints for Configuration**

```java
@RestController
@RequestMapping("/api/confidence-strategy")
@Slf4j
public class ConfidenceStrategyController {

    @Autowired
    private ConfidenceStrategyConfigRepository configRepository;
    
    @Autowired
    private ConfidenceSignalSummaryRepository summaryRepository;

    // Trader selects confidence threshold
    @PostMapping("/config")
    public ResponseEntity<ConfidenceStrategyConfig> setConfidenceThreshold(
            @RequestHeader("Authorization") String userId,
            @RequestBody ConfidenceThresholdRequest request) {
        
        ConfidenceStrategyConfig config = new ConfidenceStrategyConfig();
        config.setTraderId(UUID.fromString(userId));
        config.setStrategyName("CONFIDENCE_" + request.getThreshold());
        config.setMinConfidenceThreshold(request.getThreshold());
        config.setEnabled(true);
        
        configRepository.save(config);
        
        log.info("✅ Trader {} set confidence threshold to {}",
                 userId, request.getThreshold());
        
        return ResponseEntity.ok(config);
    }

    // Get today's signal count by threshold
    @GetMapping("/today/signal-count")
    public ResponseEntity<SignalCountByThreshold> getTodaySignalCount() {
        List<Integer> thresholds = Arrays.asList(60, 70, 80, 90);
        Map<Integer, Long> counts = new HashMap<>();
        
        for (Integer threshold : thresholds) {
            long count = signalRepository.countByConfidenceAboveAndToday(threshold);
            counts.put(threshold, count);
        }
        
        return ResponseEntity.ok(SignalCountByThreshold.builder()
            .threshold60(counts.get(60))
            .threshold70(counts.get(70))
            .threshold80(counts.get(80))
            .threshold90(counts.get(90))
            .build());
    }

    // Get signals at specific threshold
    @GetMapping("/signals/{threshold}")
    public ResponseEntity<Page<StrategySignal>> getSignalsByThreshold(
            @PathVariable Integer threshold,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        
        Pageable pageable = PageRequest.of(page, size, 
            Sort.by(Sort.Direction.DESC, "createdAt"));
        
        Page<StrategySignal> signals = signalRepository
            .findByConfidenceScoreGreaterThanAndCreatedAtAfter(
                BigDecimal.valueOf(threshold),
                Instant.now().minus(Duration.ofDays(1)),
                pageable
            );
        
        return ResponseEntity.ok(signals);
    }

    // Dashboard: Real-time signal statistics
    @GetMapping("/dashboard/live-stats")
    public ResponseEntity<Map<String, Object>> getLiveStats() {
        Map<String, Object> stats = new HashMap<>();
        
        // Last hour signals by threshold
        List<Map<String, Object>> lastHour = signalRepository
            .getSignalCountByThresholdLastHour();
        
        // Daily summary
        ConfidenceSignalSummary dailySummary = summaryRepository
            .findByDate(LocalDate.now());
        
        stats.put("lastHourBreakdown", lastHour);
        stats.put("dailySummary", dailySummary);
        stats.put("timestamp", Instant.now());
        
        return ResponseEntity.ok(stats);
    }
}
```

### 5. **JPA Entities**

```java
@Entity
@Table(name = "confidence_scores", indexes = {
    @Index(name = "idx_confidence_symbol_time", columnList = "symbol,timestamp"),
    @Index(name = "idx_confidence_score", columnList = "confidence_score"),
    @Index(name = "idx_confidence_timestamp", columnList = "timestamp")
})
@Data
@NoArgsConstructor
public class ConfidenceScore {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false, length = 20)
    private String symbol;
    
    @Column(nullable = false)
    private Instant timestamp;
    
    @Column(name = "confidence_score")
    private Integer confidenceScore;  // 0-100
    
    private Integer buyerPressure;
    private Integer sellerPressure;
    private Integer liquidityScore;
    
    @Column(length = 30)
    private String signalStrength;
    
    private Instant createdAt = Instant.now();
}

@Entity
@Table(name = "confidence_strategy_config")
@Data
@NoArgsConstructor
@Builder
public class ConfidenceStrategyConfig {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false)
    private UUID traderId;
    
    @Column(nullable = false, length = 128)
    private String strategyName;
    
    @Column(name = "min_confidence_threshold")
    private Integer minConfidenceThreshold;  // 60, 70, 80, or 90
    
    private Boolean enabled = true;
    
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();
}
```

---

## 📊 EXAMPLE: HOW IT WORKS

### Minute 1:
```
Order books received for SBIN, HDFC, INFY, etc.
↓
Confidence calculator processes all Nifty 100
↓
SBIN: 85 confidence ✅
HDFC: 72 confidence ✅
INFY: 68 confidence ⚠️
RELIANCE: 45 confidence ❌
TCS: 92 confidence ✅
...

Stored in confidence_scores table
```

### Minute 2:
```
Signal generator processes confidence scores
↓
Trader A selected threshold = 70
├─ SBIN (85) → Signal generated ✅
├─ HDFC (72) → Signal generated ✅
├─ INFY (68) → Signal NOT generated ❌
├─ RELIANCE (45) → Signal NOT generated ❌
└─ TCS (92) → Signal generated ✅

Stored in strategy_signals table as "CONFIDENCE_BASED_70"
```

### Dashboard View:
```
Today's Confidence-Based Signals:

Threshold 60: 247 signals (Trader A selected)
├─ Hit target: 132 (53.4%)
├─ Hit SL: 58 (23.5%)
└─ Still open: 57 (23.1%)

Threshold 70: 184 signals (Trader B selected)
├─ Hit target: 118 (64.1%)
├─ Hit SL: 33 (17.9%)
└─ Still open: 33 (17.9%)

Threshold 80: 98 signals (Trader C selected)
├─ Hit target: 76 (77.6%)
├─ Hit SL: 15 (15.3%)
└─ Still open: 7 (7.1%)

Threshold 90: 21 signals (Trader D selected)
├─ Hit target: 21 (100%)
├─ Hit SL: 0 (0%)
└─ Still open: 0 (0%)
```

---

## 📈 BENEFITS

✅ **Trader Flexibility**: Each trader picks their comfort threshold  
✅ **Automatic Signal Generation**: No manual intervention  
✅ **Full Tracking**: Every signal stored with confidence score  
✅ **Performance Analytics**: See hit rates by confidence level  
✅ **Data-Driven**: Empirical evidence of confidence vs. profitability  
✅ **Scalable**: Handles all Nifty 100 symbols per minute  

---

## 🚀 IMPLEMENTATION TIMELINE

**Week 2:**
- [ ] Create database tables (confidence_scores, config)
- [ ] Build ConfidenceScoreCalculatorService
- [ ] Add REST endpoints for trader configuration
- [ ] Create dashboard widgets

**Week 3:**
- [ ] Build ConfidenceBasedSignalGeneratorService
- [ ] Integrate with strategy_signals table
- [ ] Add signal tracking and analytics
- [ ] Daily summary reports

**Week 4:**
- [ ] Performance validation
- [ ] Trader testing & feedback
- [ ] Fine-tune thresholds
- [ ] Go live

---

## 💾 DATABASE MIGRATION

```sql
-- V94__create_confidence_strategy_system.sql

CREATE TABLE confidence_scores (
    id SERIAL PRIMARY KEY,
    symbol VARCHAR(20) NOT NULL,
    timestamp TIMESTAMPTZ NOT NULL,
    confidence_score INTEGER CHECK (confidence_score >= 0 AND confidence_score <= 100),
    buyer_pressure INTEGER,
    seller_pressure INTEGER,
    liquidity_score INTEGER,
    signal_strength VARCHAR(30),
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_confidence_symbol_time ON confidence_scores(symbol, timestamp DESC);
CREATE INDEX idx_confidence_score ON confidence_scores(confidence_score DESC);
CREATE INDEX idx_confidence_timestamp ON confidence_scores(timestamp DESC);

CREATE TABLE confidence_strategy_config (
    id SERIAL PRIMARY KEY,
    trader_id UUID NOT NULL,
    strategy_name VARCHAR(128) NOT NULL,
    min_confidence_threshold INTEGER,
    enabled BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_config_trader ON confidence_strategy_config(trader_id);
CREATE INDEX idx_config_enabled ON confidence_strategy_config(enabled);
```

---

## ✅ READY TO BUILD?

This system will give you:
1. **Confidence scores** for all Nifty 100 every minute
2. **Configurable thresholds** for traders
3. **Auto-generated signals** based on selection
4. **Complete tracking** of all signals
5. **Performance metrics** by confidence level

**Shall we start building?** 🚀
