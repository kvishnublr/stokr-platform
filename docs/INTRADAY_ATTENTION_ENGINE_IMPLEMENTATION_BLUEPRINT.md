# Stokr Intraday Attention Engine Implementation Blueprint

Date: 2026-06-12

Status: Design document only. No implementation yet.

## 1. Product Goal

Stokr should not become another buy/sell signal machine. The platform already generates many signals. The real product problem is prioritization:

```text
72 signals/day
-> Which 5 deserve attention?
-> Which 2 deserve capital?
```

The new engine should rank where high-quality intraday participation is forming in the cash market. It should answer:

```text
Where is money flowing right now?
Why is this ranked high?
Is the opportunity heating up or cooling down?
What would invalidate it?
Is it tradeable enough for cash execution?
```

The output is an attention and opportunity ranking layer, not an auto-trade decision layer.

## 2. Core Design Principle

Separate Stokr into two different responsibilities:

```text
Signal Generation
Existing strategy engines detect patterns and produce signals.

Signal Prioritization
New attention engine ranks symbols/signals by market participation, relative strength, sector flow, volume velocity, structure, and tradeability.
```

The new layer must be able to rank:

- Persisted production signals from `StrategySignalEntity`
- Live market mover rows from `LiveIntradayMoverService`
- Ranking-board setups from `CurrentSetup`
- Future non-signal opportunities, such as relative-strength leaders with no trade signal yet

This is important because the best cash-market opportunity may not always be attached to an existing strategy signal.

## 3. Existing Project Fit

The codebase already has most foundation pieces:

### Existing modules

| Module | Current role | New role |
|---|---|---|
| `stokr-marketdata` | Candle/tick storage and query services | Source of 1m/5m bars, volume velocity, opening range, index/sector candles |
| `stokr-strategy` | Strategy execution, signal pipeline, intraday services | Home of the new attention engine |
| `stokr-websocket` | Realtime broadcast bridge | Optional later: stream attention rank updates |
| `stokr-ui` | Trader/admin dashboards | Display attention score, reason codes, ranking changes, sector flow |
| `stokr-common` | Shared API/events/runtime utilities | Optional later: shared DTO/event if WebSocket streaming is added |

### Existing backend classes to reuse

| Class | Current behavior | How we use it |
|---|---|---|
| `com.stokr.intraday.service.UnifiedSignalTruthService` | Builds ADV terminal payload by merging live movers, persisted signals, audits, sectors, risk, performance | Main integration point. It will call the new opportunity ranking service before sorting rows |
| `com.stokr.intraday.service.LiveIntradayMoverService` | Builds live scanner rows from real-time setup board and market movers | Replace or augment its current `activityScore` logic with attention components |
| `com.stokr.intraday.engine.SetupRankingEngine` | Calculates quality score from probability, risk/reward, confidence, expected value | Keep as downstream trade-quality ranking, not the primary attention score |
| `com.stokr.intraday.engine.MarketRegimeDetector` | Detects `TRENDING_UP`, `TRENDING_DOWN`, `CHOPPY`, `VOLATILE`, `QUIET` using NIFTY-style inputs | Extend into richer market context engine with market quality and breadth |
| `com.stokr.intraday.engine.ProbabilityAdjustmentEngine` | Adjusts setup probability using regime, time of day, sector momentum, recent performance | Keep for setup probability. Do not use as the primary attention score |
| `com.stokr.intraday.metrics.OrderFlowMetricsService` | Reads order-flow snapshots and returns pressure/liquidity enhancement | Optional booster for attention and tradeability |
| `com.stokr.marketdata.service.MarketDataQueryService` | Loads candles with fallback and interval aggregation | Main market data access for RS, volume velocity, opening range, sector/index metrics |
| `com.stokr.strategy.repository.StrategySignalRepository` | Reads persisted production signals | Source of signal candidates for prioritization |
| `com.stokr.strategy.catalog.StrategyUniverseResolverService` | Resolves strategy universe symbols | Source for universe construction |
| `com.stokr.strategy.repository.StrategyUniverseGroupRepository` | Resolves universe groups | Reuse for NIFTY_50/NIFTY_100/cash universe |
| `com.stokr.strategy.repository.StrategyUniverseSymbolRepository` | Resolves enabled universe symbols | Reuse for scanning candidate symbols |

### Existing UI/API classes to reuse

| File | Current role | New role |
|---|---|---|
| `stokr-strategy/src/main/java/com/stokr/intraday/controller/AdvIntelligenceDashboardController.java` | Serves `/api/v1/adv-dashboard/terminal`, `/movers`, `/snapshot` | Add or expose attention-specific fields through terminal payload |
| `stokr-ui/src/api/advDashboard.ts` | TypeScript DTOs and API calls for ADV terminal | Add attention score, tradeability score, reason codes, score deltas, RS/volume/structure fields |
| `stokr-ui/src/pages/AdvEnhancedDashboard.tsx` | Main advanced dashboard page | Add the flagship Opportunity/Attention screen and update existing tables |
| `stokr-ui/src/pages/premium/PremiumIntradayTerminal.tsx` | Premium terminal experience | Later: add attention cards and sector/RS panels |
| `stokr-ui/src/pages/intraday/IntradayCockpitPage.tsx` | Intraday cockpit | Later: add compact attention radar |

## 4. Target Architecture

```text
MarketDataQueryService
    |
    v
Market Context Engine
Sector Rotation Engine
Relative Strength Engine
Volume Velocity Engine
Opening Range Engine
Tradeability Engine
Optional Booster Engines
    |
    v
Opportunity Ranking Service
    |
    v
UnifiedSignalTruthService
    |
    v
/api/v1/adv-dashboard/terminal
    |
    v
stokr-ui ADV terminal
```

The new engine should be deterministic, explainable, and easy to backtest. AI should explain ranks and changes after rules compute the score. AI should not produce the score directly.

## 5. Proposed Backend Package

Create a new package:

```text
stokr-strategy/src/main/java/com/stokr/intraday/attention
```

### New classes

| Class | Responsibility |
|---|---|
| `AttentionCandidate` | Internal candidate object representing one symbol/opportunity before scoring |
| `AttentionScore` | Component scores, total attention score, confidence band, reason codes |
| `TradeabilityScore` | Liquidity, spread, volatility, slippage, stale-data and circuit-risk scoring |
| `OpportunityScore` | Final ranking object combining attention, tradeability, boosters, deltas and display metadata |
| `AttentionReasonCode` | Enum of reason codes shown to trader |
| `TradeType` | Enum: `CONTINUATION`, `REVERSAL`, `GAP_CONTINUATION`, `GAP_FAILURE`, `ACCUMULATION`, `BREAKDOWN`, `WATCH_ONLY` |
| `AttentionEngineProperties` | Configurable weights and thresholds from YAML |
| `MarketContextEngine` | Computes market regime, market quality score, breadth alignment |
| `SectorRotationEngine` | Ranks sectors and produces sector strength score |
| `RelativeStrengthEngine` | Computes stock RS vs NIFTY, RS vs sector, RS persistence and rank |
| `VolumeVelocityEngine` | Computes same-time volume multiple and volume acceleration |
| `OpeningRangeEngine` | Detects ORB acceptance/rejection, gap continuation/failure, VWAP acceptance/rejection context |
| `TradeabilityEngine` | Computes whether a high-attention candidate is actually tradable |
| `OpportunityRankingService` | Main orchestrator. Produces ranked `OpportunityScore` list |
| `OpportunityHistoryService` | Keeps short-lived score history for 5m/15m score deltas |
| `OpportunityTerminalMapper` | Maps `OpportunityScore` into ADV dashboard row fields |

### Optional later classes

| Class | Responsibility |
|---|---|
| `OptionsBoosterEngine` | Adds small OI/options confirmation boost for F&O names |
| `DepthImbalanceBoosterEngine` | Adds small order book imbalance boost |
| `AiOpportunityExplanationService` | Generates natural-language explanation from deterministic reason codes |
| `OpportunityRealtimePublisher` | Publishes rank updates over WebSocket |
| `AttentionBacktestService` | Replays historical days to validate components and weights |

## 6. Data Model

### AttentionCandidate

Purpose: normalize different sources into one scoring input.

Fields:

```java
public record AttentionCandidate(
        String symbol,
        String source,
        String strategy,
        String side,
        BigDecimal ltp,
        Instant timestamp,
        StrategySignalEntity signal,
        CurrentSetup setup,
        List<MarketdataCandle> oneMinuteBars,
        List<MarketdataCandle> fiveMinuteBars,
        String sector,
        boolean hasExistingSignal
) {}
```

Sources:

- `PRODUCTION_SIGNAL`
- `LIVE_MARKET`
- `RANKING_BOARD`
- `RELATIVE_STRENGTH_SCAN`
- `SECTOR_ROTATION_SCAN`

### AttentionScore

Fields:

```java
public record AttentionScore(
        int total,
        int relativeStrengthScore,
        int volumeVelocityScore,
        int sectorStrengthScore,
        int marketBreadthScore,
        int openingRangeScore,
        int structureQualityScore,
        List<AttentionReasonCode> reasonCodes,
        String confidenceBand
) {}
```

### TradeabilityScore

Fields:

```java
public record TradeabilityScore(
        int total,
        int liquidityScore,
        int spreadScore,
        int volatilityFitScore,
        int slippageRiskScore,
        int dataFreshnessScore,
        boolean actionable,
        List<String> warnings
) {}
```

### OpportunityScore

Fields:

```java
public record OpportunityScore(
        int rank,
        String symbol,
        String side,
        TradeType tradeType,
        int attentionScore,
        int tradeabilityScore,
        int opportunityScore,
        int attentionDelta5m,
        int attentionDelta15m,
        int rsRank,
        int sectorRank,
        BigDecimal expectedMoveLowPct,
        BigDecimal expectedMoveHighPct,
        String invalidation,
        List<AttentionReasonCode> reasonCodes,
        Map<String, Object> components
) {}
```

## 7. Scoring Model

### Attention Score

Initial V1 weights:

```text
35% Relative Strength
20% Volume Velocity
15% Sector Strength
10% Market Breadth
10% Opening Range Behavior
10% Structure Quality
```

Formula:

```text
attentionScore =
    relativeStrengthScore * 0.35
  + volumeVelocityScore   * 0.20
  + sectorStrengthScore   * 0.15
  + marketBreadthScore    * 0.10
  + openingRangeScore     * 0.10
  + structureQualityScore * 0.10
```

Output range: `0..100`.

### Tradeability Score

Initial V1 weights:

```text
30% Liquidity
20% Spread
20% Volatility Fit
15% Slippage Risk
15% Data Freshness
```

Tradeability should answer:

```text
Can a trader realistically enter/exit this cash stock intraday without ugly slippage?
```

Suggested thresholds:

| Score | Label | Meaning |
|---|---|---|
| `80..100` | `ACTIONABLE` | Good enough for active attention |
| `60..79` | `TRADEABLE_WITH_CAUTION` | Watch size, spread, volatility |
| `40..59` | `WATCH_ONLY` | Interesting but not capital-ready |
| `<40` | `DO_NOT_TRADE` | Liquidity/spread/data risk too high |

### Final Opportunity Score

Recommended formula:

```text
opportunityScore =
    attentionScore * 0.75
  + tradeabilityScore * 0.25
  + boosterPoints
```

Cap final score at 100.

Booster points must never dominate:

| Booster | Max points |
|---|---:|
| OI confirmation | +4 |
| Options activity confirmation | +3 |
| Depth imbalance confirmation | +4 |
| Existing high-quality signal | +3 |
| Clean entry/exit plan already available | +2 |

Hard rule:

```text
No OI should never reject a cash opportunity.
OI can only enhance.
```

## 8. Engine Details

### 8.1 MarketContextEngine

Affected existing class:

```text
stokr-strategy/src/main/java/com/stokr/intraday/engine/MarketRegimeDetector.java
```

Recommendation:

- Keep `MarketRegimeDetector` for backward compatibility.
- Create `MarketContextEngine` in the new attention package.
- Internally call or reuse `MarketRegimeDetector` where useful.
- Add breadth and market quality that the old detector does not currently model.

Inputs:

- NIFTY 50 1m and 5m candles
- NIFTY BANK or key index candles if available
- Universe advancer/decliner count
- Sector breadth
- Volatility expansion from intraday range
- Session state from `NseMarketSession`

Outputs:

```text
marketRegime: BULLISH, BEARISH, NEUTRAL, VOLATILE, TREND_DAY, RANGE_DAY
marketQualityScore: 0..100
breadthScore: 0..100
breadthAlignment: BULLISH, BEARISH, MIXED
```

Exit criteria:

- Returns stable snapshot in under 200 ms for NIFTY_100 universe.
- No exception if index candles are missing.
- Falls back to neutral market context if data is stale.
- Unit tests cover bullish, bearish, neutral, volatile, range-day cases.

### 8.2 SectorRotationEngine

New class:

```text
stokr-strategy/src/main/java/com/stokr/intraday/attention/SectorRotationEngine.java
```

Inputs:

- Sector symbol mapping from universe metadata or static mapping.
- Sector index candles if available.
- Fallback: aggregate stock returns by sector.

Outputs:

```text
sectorName
sectorRank
sectorStrengthScore
sectorReturnPct
sectorBreadth
leadershipStocks
```

V1 fallback if sector index data is missing:

```text
sectorReturn = average intraday return of enabled stocks in sector
sectorBreadth = advancers / total sector stocks
sectorStrengthScore = normalized rank + breadth
```

Exit criteria:

- Ranks sectors every minute.
- Missing sector mapping does not break terminal.
- Each candidate gets a sector label, even if `UNKNOWN`.
- Top and bottom sectors match manual sample calculations on replay day.

### 8.3 RelativeStrengthEngine

New class:

```text
stokr-strategy/src/main/java/com/stokr/intraday/attention/RelativeStrengthEngine.java
```

This is the core product engine.

Inputs:

- Stock 1m/5m candles
- NIFTY 50 1m/5m candles
- Sector return from `SectorRotationEngine`

Metrics:

```text
stockReturnFromOpen
niftyReturnFromOpen
sectorReturnFromOpen
rsVsNifty = stockReturn - niftyReturn
rsVsSector = stockReturn - sectorReturn
rsPersistence = percentage of last N bars where stock outperformed benchmark
rsRank = cross-sectional rank within universe
```

Initial score:

```text
rsScore =
    40% rsVsNifty rank
  + 35% rsVsSector rank
  + 25% rsPersistence
```

Reason codes:

- `RS_TOP_2_PERCENT`
- `RS_TOP_5_PERCENT`
- `OUTPERFORMING_NIFTY`
- `OUTPERFORMING_SECTOR`
- `RS_PERSISTENT`
- `RS_DIVERGING_POSITIVE`
- `RS_DIVERGING_NEGATIVE`

Exit criteria:

- Produces top 20 strongest and top 20 weakest every minute.
- Handles negative market days correctly, for example stock +1.7% while NIFTY -0.8% ranks high.
- Unit tests verify RS math and ranking order.
- Replay test confirms no look-ahead bias.

### 8.4 VolumeVelocityEngine

New class:

```text
stokr-strategy/src/main/java/com/stokr/intraday/attention/VolumeVelocityEngine.java
```

Problem solved:

```text
Delivery data is mostly tomorrow's signal.
Intraday needs volume velocity.
```

Metrics:

```text
volume1m
volume5m
volume15m
sameTimeAvgVolume1m
sameTimeAvgVolume5m
sameTimeAvgVolume15m
volumeVelocity15m = current15mVolume / sameTimeAvg15mVolume
volumeAcceleration = current5mVelocity - prior5mVelocity
```

V1 fallback if same-time historical baselines are missing:

```text
volumeVelocity = currentSessionVolume / average volume of recent same timeframe bars
```

Reason codes:

- `VOLUME_2X`
- `VOLUME_4X`
- `VOLUME_ACCELERATING`
- `VOLUME_FADING`
- `PARTICIPATION_EXPANDING`

Exit criteria:

- Correctly calculates 1m/5m/15m volume velocity.
- Marks volume as `UNKNOWN` instead of zero when baseline is unavailable.
- Does not rank illiquid spikes high only because volume multiple is mathematically large from tiny baseline.

### 8.5 OpeningRangeEngine

New class:

```text
stokr-strategy/src/main/java/com/stokr/intraday/attention/OpeningRangeEngine.java
```

Metrics:

```text
openingRangeHigh15m
openingRangeLow15m
openingRangeHigh30m
openingRangeLow30m
gapPct
pricePositionVsOpeningRange
orbAcceptance
orbRejection
gapContinuation
gapFailure
vwapAcceptance
vwapRejection
```

Important note:

VWAP can be used as market structure context, not as a blind indicator signal.

Trade type mapping:

| Structure | Trade type |
|---|---|
| Holds above OR high with volume and sector support | `CONTINUATION` |
| Gap up fails OR high/OR low with weak sector | `GAP_FAILURE` |
| Gap up holds above open and sector strong | `GAP_CONTINUATION` |
| Breaks below OR low with weakness | `BREAKDOWN` |
| Price flat, volume rising, RS improving | `ACCUMULATION` |

Exit criteria:

- OR values are frozen after configured window.
- Gap logic uses previous close and today open.
- No opening range score before sufficient bars exist.
- Unit tests cover gap continuation, gap failure, ORB acceptance, ORB rejection.

### 8.6 TradeabilityEngine

New class:

```text
stokr-strategy/src/main/java/com/stokr/intraday/attention/TradeabilityEngine.java
```

Inputs:

- Recent candle volume
- Price
- Spread/order-flow data from `OrderFlowMetricsService` if available
- Data freshness from latest candle/tick time
- Intraday volatility/range

Hard warnings:

- Data stale
- No recent candles
- Extremely low volume
- Very wide spread, if order book data is available
- Excessive volatility against stop size
- Circuit/price-band risk if available later

Tradeability classifications:

```text
ACTIONABLE
TRADEABLE_WITH_CAUTION
WATCH_ONLY
DO_NOT_TRADE
```

Exit criteria:

- High-attention illiquid stocks are demoted to watch-only.
- Liquid large caps are not unfairly penalized when order book data is missing.
- Tradeability reason is visible in API and UI.

### 8.7 OpportunityRankingService

New class:

```text
stokr-strategy/src/main/java/com/stokr/intraday/attention/OpportunityRankingService.java
```

Main method:

```java
public List<OpportunityScore> rank(
        List<AttentionCandidate> candidates,
        OpportunityRankingContext context
)
```

Responsibilities:

1. Deduplicate candidates by symbol.
2. Load or reuse 1m/5m candles.
3. Compute market context.
4. Compute sector scores.
5. Compute relative strength.
6. Compute volume velocity.
7. Compute opening range behavior.
8. Compute tradeability.
9. Apply small boosters.
10. Compute final score and rank.
11. Add reason codes and invalidation.
12. Store score history for deltas.

Sorting:

```text
1. opportunityScore desc
2. attentionScore desc
3. tradeabilityScore desc
4. attentionDelta15m desc
5. volumeVelocity desc
```

Exit criteria:

- Top rows are deterministic for the same input.
- Runtime under 1 second for 100 symbols using cached candles.
- No row gets `opportunityScore > 100`.
- Reason codes explain at least 80% of score contribution.

## 9. API Changes

Primary endpoint remains:

```text
GET /api/v1/adv-dashboard/terminal
```

This avoids frontend churn and keeps existing terminal integration working.

### Add fields to `scannerRows`

Current UI type:

```text
stokr-ui/src/api/advDashboard.ts -> AdvScannerRow
```

Add:

```ts
attentionScore?: number;
tradeabilityScore?: number;
opportunityScore?: number;
attentionDelta5m?: number;
attentionDelta15m?: number;
tradeabilityLabel?: string;
tradeType?: string;
sectorName?: string;
sectorRank?: number;
relativeStrengthScore?: number;
rsRank?: number;
rsVsNifty?: number;
rsVsSector?: number;
rsPersistence?: number;
volumeVelocity1m?: number;
volumeVelocity5m?: number;
volumeVelocity15m?: number;
volumeAcceleration?: number;
openingRangeState?: string;
structureQualityScore?: number;
marketQualityScore?: number;
expectedMoveLowPct?: number;
expectedMoveHighPct?: number;
reasonCodes?: string[];
warnings?: string[];
scoreComponents?: Record<string, number>;
```

### Add top-level terminal sections

```json
{
  "attention": {
    "marketQualityScore": 74,
    "topAttentionScore": 94,
    "actionableCount": 5,
    "watchOnlyCount": 13,
    "heatingUpCount": 4,
    "coolingDownCount": 7
  },
  "relativeStrength": {
    "leaders": [],
    "weakness": []
  },
  "sectorRotation": {
    "leaders": [],
    "laggards": []
  },
  "volumeIntelligence": {
    "explosions": [],
    "fading": []
  },
  "openingRangeRadar": {
    "continuations": [],
    "failures": [],
    "rejections": []
  }
}
```

## 10. UI Changes

### Primary affected file

```text
stokr-ui/src/pages/AdvEnhancedDashboard.tsx
```

Suggested V1 UI layout:

```text
Tab: Intelligence or Dashboard

1. Market Context Strip
   - Market regime
   - Market quality score
   - Breadth
   - Strongest sector
   - Weakest sector

2. AI Opportunities / Attention Table
   - Rank
   - Symbol
   - Attention
   - Tradeability
   - Opportunity
   - Score 15m
   - Trade type
   - Sector rank
   - RS rank
   - Volume velocity
   - Structure
   - Expected move
   - Invalidation
   - Reason codes

3. Relative Strength Leaders
   - Top 20 strongest
   - Top 20 weakest

4. Sector Rotation
   - Ranked sector table
   - Leadership stocks per sector

5. Volume Intelligence
   - 1m/5m/15m volume explosions
   - Fading volume warnings

6. Opening Range Radar
   - ORB acceptance
   - ORB rejection
   - Gap continuation
   - Gap failure
```

Design guidance:

- Do not show `BUY`/`SELL` as the main output.
- Show `ATTENTION`, `TRADEABILITY`, `TRADE TYPE`, `INVALIDATION`.
- Keep execution buttons separate from attention ranking.
- Use compact dense tables, not oversized marketing cards.
- Use badges/pills for reason codes.
- Use score change arrows/icons for heating/cooling.

### Additional UI files likely affected

| File | Change |
|---|---|
| `stokr-ui/src/api/advDashboard.ts` | Add DTO fields and section types |
| `stokr-ui/src/pages/AdvEnhancedDashboard.tsx` | Add opportunity ranking table and intelligence panels |
| `stokr-ui/src/lib/intradaySignals.ts` | Optional helper formatting for attention rows |
| `stokr-ui/src/lib/intradaySetups.ts` | Optional trade type/structure label helpers |
| `stokr-ui/src/components/data/DataGrid.tsx` | Reuse if table supports required columns |
| `stokr-ui/src/pages/premium/PremiumIntradayTerminal.tsx` | Later premium terminal integration |
| `stokr-ui/src/pages/intraday/IntradayCockpitPage.tsx` | Later compact cockpit integration |

## 11. Database Changes

V1 can run without durable database tables by using:

- Existing candles from `marketdata_candles`
- Existing signals from `strategy_signals`
- Existing order-flow snapshots if enabled
- In-memory short-term score history

Recommended V1.1 durable tables:

### `intraday_opportunity_snapshots`

Purpose: store ranked snapshots for replay, diagnostics, and backtesting.

Columns:

```text
id uuid primary key
snapshot_time timestamptz not null
symbol varchar(32) not null
rank int not null
side varchar(12)
trade_type varchar(40)
attention_score int not null
tradeability_score int not null
opportunity_score int not null
attention_delta_5m int
attention_delta_15m int
sector_name varchar(80)
sector_rank int
rs_rank int
rs_vs_nifty numeric(8,4)
rs_vs_sector numeric(8,4)
volume_velocity_1m numeric(10,4)
volume_velocity_5m numeric(10,4)
volume_velocity_15m numeric(10,4)
opening_range_state varchar(60)
invalidation text
reason_codes jsonb
warnings jsonb
components_json jsonb
created_at timestamptz default now()
```

Indexes:

```text
idx_opportunity_snapshots_time_rank(snapshot_time, rank)
idx_opportunity_snapshots_symbol_time(symbol, snapshot_time desc)
idx_opportunity_snapshots_score(snapshot_time, opportunity_score desc)
```

### `intraday_sector_rotation_snapshots`

Purpose: store sector rank history.

Columns:

```text
id uuid primary key
snapshot_time timestamptz not null
sector_name varchar(80) not null
rank int not null
sector_strength_score int not null
sector_return_pct numeric(8,4)
sector_breadth numeric(8,4)
leadership_symbols jsonb
created_at timestamptz default now()
```

## 12. Configuration

Add to `stokr-strategy/src/main/resources/application.yml`:

```yaml
stokr:
  attention:
    enabled: true
    universe-group: NIFTY_100
    scan-limit: 120
    cache-ttl-ms: 10000
    weights:
      relative-strength: 35
      volume-velocity: 20
      sector-strength: 15
      market-breadth: 10
      opening-range: 10
      structure-quality: 10
    tradeability:
      min-actionable-score: 80
      min-watch-score: 60
      stale-candle-seconds: 180
      min-session-volume: 100000
    boosters:
      oi-confirmation-max: 4
      options-flow-max: 3
      depth-imbalance-max: 4
      existing-signal-max: 3
      trade-plan-max: 2
```

## 13. Integration Plan By Sprint

### Sprint 1: Relative Strength Engine

Scope:

- Add `RelativeStrengthEngine`
- Add candidate universe resolution
- Add top 20 strongest/weakest to terminal response
- Add basic UI panel

Affected classes/files:

- Add `RelativeStrengthEngine`
- Add `AttentionCandidate`
- Add `OpportunityRankingService` shell
- Modify `UnifiedSignalTruthService`
- Modify `AdvScannerRow` in `stokr-ui/src/api/advDashboard.ts`
- Modify `AdvEnhancedDashboard.tsx`
- Add tests under `stokr-strategy/src/test/java/com/stokr/intraday/attention`

Exit criteria:

- API exposes RS leaders/weakness.
- UI shows top 20 strongest/weakest.
- RS math verified against manual sample.
- No existing signal execution behavior changes.

### Sprint 2: Sector Rotation Engine

Scope:

- Add sector ranking.
- Attach `sectorName`, `sectorRank`, `sectorStrengthScore` to each row.
- Show strongest/weakest sectors.

Affected classes/files:

- Add `SectorRotationEngine`
- Modify `OpportunityRankingService`
- Modify `UnifiedSignalTruthService`
- Modify `AdvEnhancedDashboard.tsx`

Exit criteria:

- Every row has a sector field.
- Sector rankings update every terminal refresh.
- Missing mapping degrades to `UNKNOWN`, not failure.

### Sprint 3: Volume Velocity Engine

Scope:

- Add 1m/5m/15m volume velocity.
- Add volume acceleration.
- Add reason codes for expansion/fading.

Affected classes/files:

- Add `VolumeVelocityEngine`
- Modify `OpportunityRankingService`
- Modify `LiveIntradayMoverService` to stop relying on crude log-volume activity scoring where attention score exists
- Modify API DTO and UI table columns

Exit criteria:

- Top volume expansion rows match manual checks.
- Low-baseline illiquid spikes are capped.
- UI clearly displays volume velocity and acceleration.

### Sprint 4: Opening Range Intelligence

Scope:

- Add ORB/gap/structure context.
- Add trade type classification.
- Add invalidation text.

Affected classes/files:

- Add `OpeningRangeEngine`
- Modify `OpportunityRankingService`
- Modify `AdvTradePlanEnricher` only if we want existing trade plans to use the new invalidation context
- Modify UI with opening range radar panel

Exit criteria:

- ORB acceptance/rejection tests pass.
- Gap continuation/failure examples classify correctly.
- Invalidation is visible for top rows.

### Sprint 5: Full Attention and Tradeability Score

Scope:

- Add `TradeabilityEngine`.
- Add final `OpportunityScore`.
- Replace terminal row sorting with opportunity ranking.

Affected classes/files:

- Add `TradeabilityEngine`
- Add `AttentionScore`, `TradeabilityScore`, `OpportunityScore`, enums
- Modify `UnifiedSignalTruthService` sorting
- Modify `AdvEnhancedDashboard.tsx` flagship table

Exit criteria:

- Rows sort by `opportunityScore`, not old `aiScore`.
- Illiquid high-momentum rows are not top actionable rows.
- Existing signal pipeline still persists and dispatches unchanged.

### Sprint 6: AI Explanation Layer

Scope:

- Generate human-readable explanation from reason codes and score deltas.
- Explain why rank changed in last 15 minutes.

Affected classes/files:

- Add `AiOpportunityExplanationService`
- Modify `OpportunityTerminalMapper`
- Modify UI row expansion or side panel

Exit criteria:

- Explanations are deterministic when AI is disabled.
- Explanation never says "buy" or "guaranteed".
- Explanation references components that actually changed.

## 14. Existing Classes To Modify

### `UnifiedSignalTruthService`

Path:

```text
stokr-strategy/src/main/java/com/stokr/intraday/service/UnifiedSignalTruthService.java
```

Changes:

- Inject `OpportunityRankingService`.
- Convert existing `scannerRows` into `AttentionCandidate` list.
- Add additional universe candidates if needed.
- Call `OpportunityRankingService.rank(...)`.
- Map scores back into `scannerRows`.
- Sort by `opportunityScore` instead of current source/status/activity/ai sort.
- Add top-level sections: `attention`, `relativeStrength`, `sectorRotation`, `volumeIntelligence`, `openingRangeRadar`.

Risk:

- This is the central terminal payload. Keep feature flag `stokr.attention.enabled`.
- If disabled or failed, fall back to existing sort.

### `LiveIntradayMoverService`

Path:

```text
stokr-strategy/src/main/java/com/stokr/intraday/service/LiveIntradayMoverService.java
```

Changes:

- Keep current live mover scanning.
- Add fields useful for attention candidates.
- Avoid using current `aiScore` as a product-grade opportunity score.
- Later, call attention service or expose raw market mover metrics.

Risk:

- Current service caches rows. Avoid duplicate heavy candle loading.

### `MarketRegimeDetector`

Path:

```text
stokr-strategy/src/main/java/com/stokr/intraday/engine/MarketRegimeDetector.java
```

Changes:

- Keep existing public behavior.
- Either call it from `MarketContextEngine` or add an adapter.
- Do not break existing tests for probability adjustments.

Risk:

- Existing strategy probability engine depends on it.

### `SetupRankingEngine`

Path:

```text
stokr-strategy/src/main/java/com/stokr/intraday/engine/SetupRankingEngine.java
```

Changes:

- No immediate change required.
- Later, expose its quality score as one component or booster.

Risk:

- Avoid replacing it with attention score. It solves a different problem.

### `AdvIntelligenceDashboardController`

Path:

```text
stokr-strategy/src/main/java/com/stokr/intraday/controller/AdvIntelligenceDashboardController.java
```

Changes:

- Usually no endpoint change needed.
- Ensure `/terminal` includes new payload sections.
- Optionally add `/attention` endpoint later for a smaller payload.

Risk:

- Keep response backward-compatible for existing UI.

### `application.yml`

Path:

```text
stokr-strategy/src/main/resources/application.yml
```

Changes:

- Add `stokr.attention.*` config.
- Add feature flag and weights.

### `advDashboard.ts`

Path:

```text
stokr-ui/src/api/advDashboard.ts
```

Changes:

- Extend `AdvScannerRow`.
- Add top-level response types for attention, RS, sector, volume, opening range.

Risk:

- Make fields optional to keep old payload working.

### `AdvEnhancedDashboard.tsx`

Path:

```text
stokr-ui/src/pages/AdvEnhancedDashboard.tsx
```

Changes:

- Add Attention/Opportunity view.
- Update table columns.
- Add compact reason code badges.
- Add score delta display.
- Keep execution controls separate.

Risk:

- Page is already large. Consider extracting components:
  - `AttentionOpportunityTable.tsx`
  - `RelativeStrengthPanel.tsx`
  - `SectorRotationPanel.tsx`
  - `VolumeIntelligencePanel.tsx`
  - `OpeningRangeRadarPanel.tsx`

## 15. Backtesting And Validation

Do not trust weights because they sound good. Validate incrementally.

### Component-level validation

For each factor, measure:

```text
Forward return after 15m, 30m, 60m
Max favorable excursion
Max adverse excursion
Hit rate for +0.5%, +1.0%, +1.5%
False-positive rate
Average slippage proxy
```

### Incremental edge test

Test:

```text
Base: Relative Strength only
Base + Sector
Base + Sector + Volume
Base + Sector + Volume + Opening Range
Base + Sector + Volume + Opening Range + Tradeability
```

Keep a component only if it improves at least one of:

- Expectancy
- Drawdown
- False positive reduction
- Time-to-target
- Tradeability filter quality

### No look-ahead rules

- At 10:15, the engine can only use data available up to 10:15.
- Opening range values freeze after configured window.
- Volume baselines must not use current incomplete future bars.
- Delivery data is not used for live intraday entries.

## 16. Exit Criteria For Full V1

Functional:

- Terminal shows top attention opportunities ranked every refresh.
- Each row has attention score, tradeability score, opportunity score.
- Each top row has reason codes and invalidation.
- UI has RS leaders, sector rotation, volume intelligence, opening range radar.
- Existing signal generation, persistence, OMS dispatch, and risk gates remain unchanged.

Technical:

- Backend compile passes.
- New unit tests pass.
- Existing strategy tests pass for touched classes.
- `/api/v1/adv-dashboard/terminal` remains backward-compatible.
- Feature flag can disable attention engine and restore old behavior.
- Runtime stays acceptable for NIFTY_100 universe.

Trading-system validation:

- Top 5 opportunities have better forward MFE than random universe baseline.
- Tradeability filter reduces low-liquidity false positives.
- Score deltas correctly identify heating/cooling candidates.
- No component depends on OI as mandatory input.

Operational:

- Missing data degrades to neutral score, not crash.
- Stale data produces warning and demotion.
- Logs identify ranking cycle time and candidate counts.
- Metrics expose scan latency, candidates ranked, actionable count, fallback count.

## 17. What Not To Do

Do not:

- Replace all existing strategy signals immediately.
- Make OI mandatory for cash-market ranking.
- Use delivery data for same-day intraday entry decisions.
- Show fake precision like `87% probability` until calibrated.
- Let AI generate buy/sell calls directly.
- Mix attention score with execution eligibility.
- Rank illiquid stocks high without tradeability demotion.
- Break existing OMS/risk/pipeline flow.

## 18. Final Product Statement

```text
Stokr does not tell traders what to buy.

Stokr tells traders where institutional-quality attention is forming,
why it matters, whether it is tradeable, whether it is heating up or cooling down,
and what would invalidate the idea.
```

That is the product advantage.

