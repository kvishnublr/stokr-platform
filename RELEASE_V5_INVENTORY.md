# Release v5 Clean Branch — Inventory of files to COPY

> This is the authoritative list of files copied from the current
> codebase into `Release_v5_clean`. **Anything not on this list is
> NOT copied. It does not exist in v5.**
>
> Total backend: ~7,200 lines (vs current 60,000+)
> Total frontend: ~3,500 lines (vs current ~25,000 in stokr-ui)
> Total new v5 code: ~1,300 lines
> **Grand total: ~12,000 lines**, 5× smaller than today.
>
> Read each section. If you spot "wait, you forgot X" — tell me.
> If you spot "why is this here?" — tell me. The whole point of
> this document is for you to challenge the list BEFORE we copy.

---

## Summary table

| Source module | What's kept | What's dropped | Files kept | Lines kept |
|---|---|---|---|---|
| stokr-common | Most shared types/events | Strategy-related events | 35 | ~1,000 |
| stokr-auth | All (entire module is auth) | Nothing | 30 | ~1,500 |
| stokr-broker | Zerodha adapter only | Dhan/Fyers/Simulated | 11 | ~600 |
| stokr-user | Broker + Telegram subdirs | Onboarding/contact/whatsapp/orchestration | 25 | ~1,600 |
| stokr-marketdata | Candle + tick + OBI tracker | Synthetic seed, simulation engine | 22 | ~1,200 |
| stokr-bootstrap | App entry + config + feed glue | Recovery/admin/automation/etc | 30 | ~1,300 |
| stokr-ui | Login + broker connect + shell | All trading/admin pages | ~15 files | ~3,500 |
| **NEW: stokr-v5** | The 12 v5 classes | — | 12 | ~1,300 |

---

## Detailed file lists

### 1. `stokr-auth` — KEEP ALL (1,500 lines, 30 files)

Confirmed: user wants full JWT + email login. Every file in this module
is needed. No subsetting required. Just copy `stokr-auth/` wholesale.

Files (from `find stokr-auth/src/main/java -name "*.java"`):
- All `config/`, `domain/`, `dto/`, `jwt/`, `mail/`, `repository/`, `security/`, `service/`, `web/`
- Test files: optional copy, recommend yes (catches regressions during copy)

**Lines: 1,500.** Largest single file: `AuthService.java` (400 lines).

---

### 2. `stokr-common` — KEEP MOST (1,000 lines, 35 files)

Shared types and events used everywhere.

**KEEP these (alphabetical, ~35 files):**
- `api/` — ApiError, ApiResponse, PageResponse (HTTP wrappers)
- `correlation/` — CorrelationIdHolder for tracing
- `crypto/` — AesGcmFieldCipher (for encrypting tokens in DB)
- `domain/BaseEntity.java`, `domain/ExitReason.java`
- `events/auth/` — AuthAuditEvents
- `events/ExecutionAlertEvent.java`
- `events/OperationalRealtimeEvent.java`
- `events/PlatformZerodhaOAuthRequiredEvent.java`
- `events/PlatformZerodhaOAuthResolvedEvent.java`
- `exception/` — all (BadRequest, NotFound, etc)
- `notification/NotificationEvent.java`, `NotificationPublisher.java`
- `pipeline/` — only PipelineQueues constants (if used)
- `simulation/SimulationModeService.java` (if needed by paper mode)
- `telemetry/` — minimal subset

**DROP these (not needed in v5):**
- `events/backtest/*` — old backtest events (we have own backtester)
- `events/ExecutionDispatchFailedEvent.java` — old dispatch model
- `events/OrderStateTransitionEvent.java` — old OMS event
- `events/SignalOutcomeEvents.java` — old strategy event
- `events/SignalPublishedEvent.java` — old strategy event
- `events/StrategyPnlUpdateEvent.java` — old strategy event
- `events/PlatformFeedReconnectRequestedEvent.java` — old recovery
- `events/realtime/RealtimeBridgeEvents.java` — old WebSocket bridge
- `backtest/BacktestReplayHolder.java` — old backtest

**Note:** I'll do a final import-graph check before copying to make sure
every kept file's imports resolve.

---

### 3. `stokr-broker` — KEEP ZERODHA ONLY (600 lines, 11 files)

Only Zerodha matters. Other broker adapters are dead weight.

**KEEP:**
- `adapter/ZerodhaAdapter.java`
- `adapter/OutboundIpRestClientFactory.java` (needed by Zerodha for IP whitelisting)
- `api/BrokerAdapter.java` (interface)
- `api/BrokerUserPositionsSource.java` (interface)
- `kite/ZerodhaKiteInstrumentResolver.java`
- `kite/ZerodhaKitePositionsParser.java`
- `model/BrokerCredentials.java`
- `model/BrokerOrderRequest.java`
- `model/BrokerOrderResponse.java`
- `model/BrokerPosition.java`
- `model/BrokerTick.java`
- `safety/BrokerLiveOrderGuard.java`

**DROP:**
- `adapter/DhanAdapter.java`
- `adapter/FyersAdapter.java`
- `model/BrokerPositionDetail.java` (used by old code)
- `registry/BrokerAdapterRegistry.java` (only needed for multi-broker)
- `simulation/SimulatedBrokerAdapter.java` (v5 has own paper sim)

**Lines: 600.** Largest: ZerodhaAdapter (~300 lines).

---

### 4. `stokr-user` — KEEP BROKER + TELEGRAM ONLY (1,600 lines, 25 files)

Most of stokr-user is onboarding, contacts, whatsapp — irrelevant for v5.

**KEEP `broker/` (excluding `historical/`):**
- `BrokerExecutionCredentialService.java`
- `BrokerMarginSyncScheduler.java`
- `PlatformFeedOperationalEvaluator.java`
- `PlatformMarketFeedService.java`
- `ZerodhaBrokerHealthService.java`
- `ZerodhaBrokerOperationsService.java`
- `ZerodhaBrokerUserPositionsSource.java`
- `ZerodhaConnectionService.java` — handles the OAuth callback (the one we just fixed)
- `ZerodhaKiteApiClient.java` — the actual HTTP client to Zerodha
- `ZerodhaOAuthDailyValidationScheduler.java`
- `ZerodhaOAuthWatchScheduler.java`
- `ZerodhaTokenRefreshScheduler.java`
- `web/TraderBrokerController.java` — broker-connect API endpoint

**DROP `broker/historical/`:**
- All 12 historical adapters (Angel, Dhan, Fyers, Upstox alternatives — we use Zerodha for everything)

**KEEP `telegram/`:**
- `TelegramBotClient.java`
- `TelegramDeliveryService.java`
- `TelegramVerificationService.java`
- `ZerodhaOAuthTelegramAlertService.java`
- `web/TelegramWebhookController.java`
- `web/TraderTelegramController.java`

**KEEP also (minimal supporting):**
- `config/UserConfig.java` (if needed for broker module wiring)
- `domain/User.java` and friends (if not in stokr-auth)
- `repository/UserRepository.java`

**DROP:**
- `onboarding/` (entire)
- `contact/` (entire)
- `whatsapp/` (entire)
- `orchestration/` (entire)
- `service/` — most (only what broker code needs)

**Lines: 1,600.**

---

### 5. `stokr-marketdata` — KEEP CORE DATA PATH (1,200 lines, 22 files)

We need: candle storage, tick storage, feed health monitor, OBI tracker
(for future ADV_CASH revival if you ever want it), query service.

**KEEP:**
- `cache/LatestPriceCache.java` — fast current-price lookup
- `domain/MarketdataCandle.java`, `MarketdataTick.java`, `MarketDataCoverage.java`
- `integrity/*` — all 5 files (data quality checks)
- `monitor/FeedHealthMonitorService.java`, `FeedHealthWebSocketState.java`
- `repository/*` — all 3 repositories
- `runtime/CandleFinalizationScheduler.java`, `InMemoryCandleManager.java`
- `service/CandleAggregationService.java`, `CandleAggregator.java`, `CandleFinalizationService.java`
- `service/MarketDataCoverageService.java`, `MarketDataQueryService.java`, `MarketDataService.java`
- `service/OrderBookPressureTracker.java` — keep for future
- `web/MarketDataController.java` — API for charts

**DROP:**
- `backtest/HistoricalDataLoadConfig.java` — v5 backtester is tiny, doesn't need this
- `seed/ReplayEquityCandleSeedService.java`, `ReplaySyntheticCandleSeeder.java` — for dev only
- `simulation/SimulatedMarketDataEngine.java` — for dev only
- `web/MarketLegacyController.java` — legacy API

**Lines: 1,200.**

---

### 6. `stokr-bootstrap` — KEEP APP WIRING ONLY (1,300 lines, 30 files)

Most of bootstrap is strategy/admin/recovery code that v5 replaces.
We keep the Spring Boot app entry + config + feed runtime + health.

**KEEP `StokrApplication.java`** (main class — rename to V5Application)

**KEEP `config/`** — most files (Redis, RabbitMQ if used, security wiring, JPA, Flyway config). Will review each in detail before copy.

**KEEP `feed/`** — the live market data feed runners (Zerodha WebSocket etc, ~14 files). These ARE the market data feed for v5.

**KEEP `health/`** — health check endpoints

**KEEP `domain/`** — base shared domain (if not duplicated elsewhere)

**KEEP one or two from `service/`** — only what bootstrap needs to wire up

**DROP:**
- `admin/` (all 5 files — old admin services)
- `audit/`, `automation/`, `controller/`, `domain/` — review each
- `recovery/` (all 13 files — old signal recovery)
- `trader/` (all 9 files — old trader features)
- `notification/` (we have Telegram from stokr-user)
- `trading/` (old trading services)
- `mail/` — handled by stokr-auth
- `metrics/`, `operational/`, `portfolio/`, `queue/`, `signal/`, `validation/` — old subsystems

**Lines: 1,300.** Need to read individual config files to be sure; this number could shift ±300.

---

### 7. `stokr-ui` — KEEP SHELL + LOGIN + BROKER CONNECT (3,500 lines)

This is the trickiest because the UI is one big React app.

**KEEP:**
- `src/main.tsx`, `src/App.tsx`, `src/index.css` (app entry)
- `src/components/` — only common UI components (buttons, layout, table). Review each.
- `src/layout/` — page shell, navigation
- `src/hooks/` — generic hooks (useAuth, useApi)
- `src/api/` — the API client wrapper
- `src/services/` — JWT handling, auth state
- `src/state/` — Redux/Zustand store IF used by login/broker (drop strategy state)
- `src/types/` — common types
- `src/lib/` — utility functions
- `src/pages/LoginPage.tsx` and friends (login flow)
- `src/pages/BrokersPage.tsx` — Zerodha connect page (909 lines — the one you used to connect Zerodha)
- `src/pages/RegisterPage.tsx`, password reset pages

**DROP (most of the UI is old admin/trading pages):**
- `src/pages/admin/AdminSafetyDiagnosticsPage.tsx` (1,213 lines)
- `src/pages/admin/AdminSignalsPage.tsx` (955 lines)
- `src/pages/admin/AdminBackfillPage.tsx` (810 lines)
- `src/pages/intraday/IntradayCockpitPage.tsx` (1,129 lines)
- `src/pages/TerminalPage.tsx` (1,076 lines)
- `src/pages/AdvEnhancedDashboard.tsx` (1,013 lines)
- `src/pages/DashboardPageExact.tsx` (1,000 lines)
- `src/pages/trader/TraderDashboard.tsx` (831 lines)
- `src/components/admin/cockpit/AdminCockpitPanels.tsx` (1,440 lines)
- All other strategy/trading/analytics specific pages and components

**ADD (new for v5):**
- `src/pages/V5DashboardPage.tsx` — the new comprehensive dashboard
- Sub-components: status tiles, trade table, equity chart, alerts feed

**Lines: ~3,500** (shell + login + broker connect + the new v5 dashboard).

---

### 8. `stokr-v5` — NEW MODULE (1,300 lines, 12 classes)

Built fresh per the architecture in `RELEASE_V5_PLAN.md` Section 5.

- MarketCalendarService (60)
- RateLimitedZerodhaClient (80)
- MinimalSignalScanner (150)
- OrbStrategy (130)
- MinimalOrderService (200)
- DynamicExitMonitor (180)
- DynamicExitDecisionRules (100)
- DisasterStopReconciler (120)
- KillSwitchService (80)
- V5DashboardController (150)
- V5DashboardService (100)
- V5AlertService (80)
- OrbBacktester (200 — tiny historical-candle replayer, NEW per user decision)

**Lines: 1,500.** (was 1,300; added 200 for the v5 backtester.)

---

## SQL migrations

Current: 460 migrations.

For v5_clean, we **squash** to a single fresh baseline:
- `V1__baseline.sql` — re-creates the small set of tables we actually need:
  - `users`, `user_roles` (from stokr-auth schema)
  - `broker_accounts`, plus token tables (from stokr-user schema)
  - `marketdata_candles`, `marketdata_ticks`, `marketdata_coverage` (from stokr-marketdata)
  - `auth_*` tables (jwt refresh, email verify, password reset)
  - that's it

- `V2__v5_tables.sql` — the 3 new v5 tables (`live_trade`, `admin_actions`, `v5_alerts`)

**Total migrations: 2.** Down from 460. Faster startup, no decades-old quirky migrations to debug.

**Note on data:** if you want to keep historical candle data, we can
re-import it from the prod DB into the v5 DB. Otherwise, fresh start —
v5 ingests candles from today onward.

---

## What we do NOT copy

For absolute clarity, here's the categorical drop list:

| Module | Drop |
|---|---|
| stokr-strategy | ENTIRE module (254 files, all 17 strategy generators, catalog scanner, smart-exit, lifecycle services, etc) |
| stokr-execution | ENTIRE module (170 files — OrderIntentProcessor, ExecutionSimulator, all the safety layers, etc) |
| stokr-oms | ENTIRE module (64 files — OMS pipeline, journal, reconciliation infra) |
| stokr-risk | ENTIRE module (42 files — cluster rule, pyramiding rule, kill switch — we write a 80-line v5 version) |
| stokr-admin | ENTIRE module (124 files — old admin features) |
| stokr-backtest | ENTIRE module (57 files — v5 has own 200-line replayer) |
| stokr-websocket | ENTIRE module (7 files — v5 polls, no WebSockets) |

**Java files dropped: ~720 of 1,038 (69%).**
**Lines dropped: ~50,000 of ~60,000 (83%).**

---

## Open questions before I execute the copy

Three things I want you to weigh in on before I start:

### Q1: Existing prod data

Do you want to:
- (a) Start v5 with a fresh DB (no historical signals, no past trades) — cleanest, but you lose any data you might want
- (b) Migrate the small useful subset (broker accounts, candles, user table) from prod DB to v5 DB — slightly more work, preserves your account + market data

**My recommendation: (b).** Migrating ~3 tables is cheap. You keep your
login working and don't have to re-import candle data.

### Q2: Run v5 alongside or replace

Once v5 boots cleanly:
- (a) Deploy v5 to a SECOND container/port (e.g. 8081) alongside the existing app for 1 week of side-by-side comparison
- (b) Cut over to v5 once Phase 1 verification passes, retire the old app

**My recommendation: (a)** for the first week. Real safety net during cutover.

### Q3: Domain name / URL

Existing app runs at `stokr.in`. For v5:
- (a) Reuse `stokr.in` — replace what's running
- (b) Use a subdomain like `v5.stokr.in` during the side-by-side period
- (c) Use a port like `stokr.in:8081`

**My recommendation: (b)** during testing. Cut DNS to v5 when ready.

---

## What happens next (after you approve)

1. You approve the inventory (with any edits you want).
2. **Next session:** I create `Release_v5_clean` as an orphan branch (or
   a new repo if you prefer — easier to reason about).
3. I copy the files per this inventory.
4. I write the new v5 module + dashboard.
5. We build, boot, verify nothing is missing.
6. **Phase 1 starts** — paper-only ORB through the new system.

Estimated time for the copy + boot + verify: 1 focused session (no
strategy work yet, just plumbing).

Then Phase 1-3 from `RELEASE_V5_PLAN.md` as planned.
