# Strategy backtest & signal volume — 2026-05-26

## Executive summary

| Finding | Detail |
|--------|--------|
| **367 signals/day** | Expected under current config (not a duplicate-emission bug): ~100 symbols × 3 strategies × ~1 scan/min × lenient filters. V55 enabled 5 strategies on **both** NIFTY_50 and NIFTY_100; scan_interval was **5s** while catalog poll is **60s**. |
| **28% win% (mostly SL Hit)** | Admin win% = `TARGET_HIT / (TARGET_HIT + SL_HIT)` only. Generators did **not** persist strategy SL/target — `SignalPriceEnrichmentService` applied generic **1.5× ATR** stops, tightening exits vs strategy intent. |
| **Property bug** | `@Value("${stokr.spike.*}")` did not bind to `stokr.strategy.spike.*` in `application.yml`, so production often used **lenient Java fallbacks** (composite ≥50, velocity 0.08%, etc.). Fixed in code. |
| **Backtest gap** | `BacktestStrategyPlugin` had **zero implementations**; `/api/backtest/replay` failed for catalog strategies. Added `RegistryTradingStrategyBacktestPlugin` beans. |

## Observed vs expected volume (order of magnitude)

Assumptions: NIFTY_100 only, 60s catalog scan, 6h session (360 min).

| Strategy | Your count | Rough ceiling (before filters) | Notes |
|----------|------------|--------------------------------|-------|
| NSE_SPIKE_DETECTION | 241 | ~100 symbols × (360/5 cooldown) if every bar fired | Lenient thresholds + 120s cooldown → ~2–3/symbol/day plausible |
| EARLY_BREAKOUT | 110 | ~1 breakout/symbol/session | 5m OR + confirmation; can re-fire on re-tests |
| VWAP_BOUNCE | 16 | Low by design | Stricter touch/bounce filters |

## Code changes (this branch)

1. **Point-in-time candles** — `StrategyCandleLoader` uses `lastBarsAscEndingAt` when `StrategyContext.asOf()` is set (replay/backtest).
2. **Strategy-native entry/SL/target** on `StrategySignal` → persisted via `StrategySignalEntityMapper` (outcome tracker uses real levels).
3. **Tighter defaults** + **YAML binding** (`stokr.strategy.spike|earlybreakout|vwapbounce`).
4. **Volume controls** — wire `SignalCooldownService` (300s), per-strategy **daily caps**, migration **V57** (NIFTY_50 off when NIFTY_100 on, scan_interval 60s).
5. **Backtest plugins** — `RegistryTradingStrategyBacktestPlugin` for NSE_SPIKE, EARLY_BREAKOUT, VWAP_BOUNCE, GAP_FILL, SECTOR_LAGGARD.

## Run backtest locally

```bash
# 1. API + DB with marketdata coverage READY for symbol/timeframe
# 2. JWT from login
export STOKR_BT_TOKEN="<access_token>"
export STOKR_API_BASE="http://localhost:8080"

python scripts/run_active_strategy_backtests.py --symbol RELIANCE --days 14
```

Or UI: **Backtests → Launcher** with `executionMode=BACKTEST`.

### Backtest results (local)

> Not executed in this session (no local DB/API). After running the script, paste metrics here:

| Strategy | Symbol | Days | Signals | Trades | Win rate | Total PnL |
|----------|--------|------|---------|--------|----------|-----------|
| NSE_SPIKE_DETECTION | | | | | | |
| EARLY_BREAKOUT | | | | | | |
| VWAP_BOUNCE | | | | | | |

## Recommended prod steps

1. **Apply Flyway V57** (or run SQL manually) — disables duplicate NIFTY_50 bindings, sets `scan_interval_seconds=60`.
2. **Restart API** so new Java defaults + property prefixes load.
3. **Optional env tuning** (start strict, relax if too quiet):

```env
STOKR_SPIKE_MIN_COMPOSITE_SCORE=75
STOKR_SPIKE_MIN_VELOCITY_PCT=0.25
STOKR_SPIKE_COOLDOWN_SECONDS=300
STOKR_SPIKE_DAILY_CAP=80
STOKR_EARLYBREAKOUT_DAILY_CAP=40
STOKR_VWAPBOUNCE_DAILY_CAP=25
STOKR_SIGNAL_COOLDOWN_SECONDS=300
```

4. **Disable unused strategies** in admin if GAP_FILL / SECTOR_LAGGARD are not in production:

```sql
UPDATE strategy_runtime_bindings b
SET runtime_enabled = false, updated_at = NOW()
FROM strategy_definitions sd
WHERE b.strategy_catalog_id = sd.id
  AND sd.strategy_key IN ('GAP_FILL', 'SECTOR_LAGGARD');
```

5. Re-run `scripts/run_active_strategy_backtests.py` on 3–5 liquid symbols; compare win rate **after** strategy-native SL/target vs prior ATR enrichment.

## Per-strategy tuning guide

| Strategy | Reduce count | Improve accuracy |
|----------|--------------|------------------|
| **NSE_SPIKE** | Raise `min-composite-score` (75+), `min-velocity-pct` (0.25+), `cooldown-seconds` (300+), daily cap 60–80 | Enable `require-continuation-candle`, `min-risk-reward` ≥ 1.5 |
| **EARLY_BREAKOUT** | Narrow session end to 12:00, `cooldown-seconds` 600+, cap 30–40/day | Raise `min-body-ratio`, `breakout-exceed-pct`, `min-volume-multiple` |
| **VWAP_BOUNCE** | Already low; cap 20–25/day | Tighter `touch-threshold-pct`, higher `min-slope-pct`, require `wasAway` (already on) |
