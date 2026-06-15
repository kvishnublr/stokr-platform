# Release v5 — The Plan
### Simple. Error-free. Yet effective.

> This document is the source of truth for the v5 work. If anything I do
> later diverges from this, you can call it out by pointing at this file.
> Last updated: 2026-06-15 after user confirmed kill-switch, multi-user,
> price filter (₹200–₹3,000), and rate limiter (1 order / 200ms).

---

## 0. Approach: clean orphan branch, copy only what's useful (CONFIRMED 2026-06-15)

User confirmed the clean-break approach. **We do NOT keep the old code as
frozen dead weight.** Instead:

1. Produce an inventory of what's genuinely useful (~8,500 lines, mostly
   broker/auth/market-data plumbing) — see Phase 0 / Appendix C below.
2. Create `Release_v5_clean` as an **orphan branch** (no git history of
   Release_v4 — clean slate).
3. Copy only the inventoried files. Everything else: gone.
4. Build the 12 new v5 classes on top.

**Result:** ~10,000 lines total (vs current ~60,000+ Java alone). 6× smaller.

**Confirmed scope decisions:**
- Auth: keep existing JWT + email login system (~1,500 lines). Multi-user
  activation in Phase 5 then needs no auth rebuild.
- Backtest: NOT copying the existing 57-file backtest module. Instead, a
  ~200-line `OrbBacktester` that replays historical candles through the
  exact `OrbStrategy` class that runs live. **Same code path** for live
  and backtest — eliminates an entire category of "backtest looked good,
  live didn't match" bugs.

## 1. Why we are doing this (the brutally honest part)

The current system has 1,038 Java files, 556 Spring beans, 64 schedulers,
460 SQL migrations and a 6-service entry pipeline (~2,000 lines) plus a
7-service exit pipeline (~2,250 lines) for ONE trade.

Every "small fix" we deploy breaks something else because the layers no
longer trust each other. We've seen this for weeks:

- Orphan positions (transaction rolled back after broker order placed)
- TIME_EXIT signals with no corresponding exit order
- Cluster rule killing 60% of ORB signals
- Silent sizing fallback to 1 share when enum string was wrong
- Bracket-order claims when Zerodha actually killed BO in 2020
- "Signals fire, then nothing" — orphan signals with no order at all

These are NOT independent bugs. They are symptoms of one root cause:
**too much code that nobody can hold in their head.**

v5 replaces the live trading path with **~1,300 lines** that does the
same job, dynamically, multi-user-ready, and debuggable in one sitting.

It does **NOT** delete the existing code. The old code stays, frozen, used
for paper backtest if you want it. All live trading routes through v5.

---

## 2. What we keep, what we add, what we ignore

| | Existing | v5 |
|---|---|---|
| Login / auth | ✅ Keep | — |
| Broker connection (Zerodha OAuth) | ✅ Keep | — |
| Market data ingestion | ✅ Keep | reuses it |
| Telegram service | ✅ Keep | reuses for alerts |
| React + Vite UI | ✅ Keep | adds 1 page |
| Backtest engine | ✅ Keep (frozen) | uses for ORB backtest |
| Strategy config pages | ✅ Keep | not touched in Phase 1-3 |
| Catalog scanner | ❌ Frozen, not used by v5 | replaced by MinimalSignalScanner |
| BOTH/PAPER/LIVE mode mess | ❌ Frozen | replaced by single mode per env |
| OrderIntentProcessor + ExecutionSimulator | ❌ Frozen | replaced by MinimalOrderService |
| Cluster rule, pyramiding rule, smart-exit | ❌ Frozen | replaced by DynamicExitMonitor |
| 17 strategy generators | ❌ Frozen | replaced by 1 (ORB) |

Phase 4 (later, optional) deletes the frozen code. Or never. Either is fine.

---

## 3. Architecture (target: ~1,300 lines, 12 classes)

```
                  ┌─────────────────────────────────────┐
                  │   MarketCalendarService             │  ← knows trading hours + holidays
                  └─────────────────────────────────────┘
                                 │
                                 ▼
  ┌──────────────────────────────────────────────────────────────┐
  │   MinimalSignalScanner @Scheduled(1m)                        │
  │   - Skips weekends, NSE holidays, outside 09:30-14:30 IST    │
  │   - Skips when STOKR_V5_TRADING_PAUSED=true (emergency)      │
  │   - For each user with ORB enabled:                          │
  │     - Evaluate ORB on 20 symbols (₹200–₹3,000 price filter)  │
  │     - Skip if user hit daily loss limit                      │
  │     - Skip if user hit consecutive-loss kill switch          │
  │     - For each fresh signal → MinimalOrderService.enterTrade │
  └──────────────────────────────────────────────────────────────┘
                                 │
                                 ▼
  ┌──────────────────────────────────────────────────────────────┐
  │   MinimalOrderService.enterTrade(userId, symbol, side, ...)  │
  │   - Pre-trade margin check (skip + log if insufficient)      │
  │   - Place Zerodha MARKET entry (via RateLimitedZerodhaClient) │
  │   - Wait for fill via order-update poll                      │
  │   - On filled qty (may be partial):                          │
  │     - Place wide disaster stop SL-M at -0.8% at broker       │
  │     - Insert ONE row into live_trade (user_id, qty actually   │
  │       filled, disaster_stop_order_id, mfe=0, status=OPEN)    │
  │   ! BROKER CALL IS THE LAST STEP IN ITS OWN METHOD !          │
  │   ! No transaction surrounds it. Orphans are impossible.      │
  └──────────────────────────────────────────────────────────────┘
                                 │
                                 ▼
  ┌──────────────────────────────────────────────────────────────┐
  │   DynamicExitMonitor @Scheduled(5s)                          │
  │   - For each OPEN live_trade across all users:               │
  │     - Read current price (from existing market data)         │
  │     - Update MFE if new high                                 │
  │     - Decide via DynamicExitDecisionRules:                   │
  │       1. logical SL hit → exit                               │
  │       2. logical target hit → exit                           │
  │       3. trailing-stop hit (after MFE >= +0.5R) → exit       │
  │       4. breakeven hit (after MFE >= +0.3R) → exit at entry  │
  │       5. session-close approaching (15:15) → exit            │
  │     - On exit decision:                                      │
  │       a. Cancel disaster_stop_order_id at broker             │
  │       b. Place MARKET sell to flatten                        │
  │       c. Update live_trade status=CLOSED, P&L                │
  │       d. If loss → check consecutive-loss kill switch        │
  └──────────────────────────────────────────────────────────────┘
                                 │
                                 ▼
  ┌──────────────────────────────────────────────────────────────┐
  │   DisasterStopReconciler @Scheduled(60s)                     │
  │   - For each OPEN live_trade: check if disaster stop fired   │
  │   - If yes (app was down): record CLOSED with broker fill    │
  │     price, alert admin via Telegram (this should be rare)    │
  │   - Detects orphans: broker positions with no live_trade row │
  │     → calls existing OrphanPositionSquareOffService          │
  └──────────────────────────────────────────────────────────────┘
                                 │
                                 ▼
  ┌──────────────────────────────────────────────────────────────┐
  │   V5DashboardController     GET /api/v5/dashboard            │
  │   - Returns one JSON snapshot (all data the page needs)      │
  │   - Per-user filtered for trader role, all-user for admin    │
  │   POST /api/v5/halt              (emergency stop)            │
  │   POST /api/v5/trade/{id}/close  (manual close one trade)    │
  │   POST /api/v5/strategy/toggle   (enable/disable ORB)        │
  │   POST /api/v5/limits            (update daily loss limit)   │
  │   All admin actions logged to admin_actions table            │
  └──────────────────────────────────────────────────────────────┘
                                 │
                                 ▼
  ┌──────────────────────────────────────────────────────────────┐
  │   /admin/v5-dashboard (React page in existing UI app)        │
  │   - Status tiles (broker, strategy, feed, daily loss)        │
  │   - Open trades table with [Close Now] buttons               │
  │   - Strategy controls + [HALT TRADING] big red button        │
  │   - Live equity curve (today, 5-sec resolution)              │
  │   - Per-trade decision timeline (click trade to expand)      │
  │   - Real-time alerts feed (last 50, auto-scroll)             │
  │   - Auto-refreshes every 2 seconds                           │
  └──────────────────────────────────────────────────────────────┘
```

**Twelve classes total. ~1,300 lines.** Each class is small enough to read
in 5 minutes and hold in your head.

---

## 4. Why this fixes every bug we've hit

| Past bug | Why v5 prevents it |
|---|---|
| Orphan positions (TX rollback after broker submit) | Broker call is the LAST step in its own non-transactional method. Nothing can roll back after. |
| TIME_EXIT signal with no exit order | No separate signal/order distinction. The `live_trade` row IS the trade. Exit places an order directly on it. |
| Cluster rule killing 60% of ORB | No cluster rule. ORB by design has one trade per symbol per day; cluster is meaningless. |
| Silent sizing fallback to 1 share | One formula: `qty = floor(₹5000 / entry_price)`. No enum. No string. |
| Bracket orders (don't exist anymore) | We use entry + disaster stop SL-M + dynamic in-app exits. No bracket orders. |
| Signals fire but no entry order | Signal IS the entry intent. `enterTrade()` either places an order or throws. Exceptions surface on the dashboard immediately. |
| Frozen disaster recovery | Restart-safe: on app startup during market hours, reload OPEN trades and resume DynamicExitMonitor automatically. |
| Stale broker data | All broker reads have 5-sec timeout + circuit breaker. If broker is sick, we pause new entries (existing trades still monitored). |

---

## 5. The 12 classes (and one table)

| # | Class | Lines | Purpose |
|---|---|---|---|
| 1 | `MarketCalendarService` | 60 | knows NSE trading days + 09:15-15:30 hours |
| 2 | `RateLimitedZerodhaClient` | 80 | wraps existing Zerodha client with 1/200ms rate limit + 5s timeout + circuit breaker |
| 3 | `MinimalSignalScanner` | 150 | @Scheduled, evaluates ORB per user, fans signals out |
| 4 | `OrbStrategy` | 130 | pure ORB evaluation function (no Spring deps) |
| 5 | `MinimalOrderService` | 200 | enterTrade method + margin check + partial-fill handling |
| 6 | `DynamicExitMonitor` | 180 | @Scheduled 5s, runs exit rules on each OPEN trade |
| 7 | `DynamicExitDecisionRules` | 100 | pure functions: should_exit_by_sl/target/trail/breakeven/time |
| 8 | `DisasterStopReconciler` | 120 | @Scheduled 60s, detects disaster-stop fills + orphans |
| 9 | `KillSwitchService` | 80 | per-user daily loss limit + consecutive losses |
| 10 | `V5DashboardController` | 150 | one GET (snapshot) + four POSTs (actions) |
| 11 | `V5DashboardService` | 100 | aggregates dashboard JSON |
| 12 | `V5AlertService` | 80 | Telegram alerts for critical events (reuses existing TelegramDeliveryService) |

**ONE migration:** `V_v5__create_v5_tables.sql` creates:
- `live_trade(id, user_id, symbol, side, qty, entry_price, logical_sl, logical_target, mfe, disaster_stop_order_id, status, opened_at, closed_at, exit_price, realized_pnl, exit_reason)`
- `admin_actions(id, user_id, action, payload_json, created_at)`
- `v5_alerts(id, user_id, level, message, created_at)`

**ONE React page:** `stokr-ui/src/admin/V5Dashboard.tsx` (~600 lines including all
components).

That's it. Twelve Java classes, one migration, one React page.

---

## 6. Hard rules we enforce in code

These are NOT polite suggestions. They are enforced by the architecture:

1. **No broker call inside a DB transaction.** Compile-time guarantee:
   `MinimalOrderService` methods are NOT annotated `@Transactional`.
   Broker call is always the last statement.

2. **One live order path.** When v5 is active, the old
   `CatalogDrivenScanScheduler` and `OrderIntentProcessor` are disabled
   via `stokr.v5.takeover=true` flag. They simply do nothing for live.

3. **No silent enum fallback.** All config values are validated at
   startup. App refuses to start with invalid config (fail fast,
   loudly).

4. **Position size formula is one line of code.** No sizing modes.
   `qty = floor(MAX_RUPEES_PER_TRADE / entry_price)`. If qty == 0 → skip.

5. **Stock price gate.** Trade only stocks priced ₹200–₹3,000.
   Outside this range → skip with one log line.

6. **Rate-limited broker client.** Every Zerodha call goes through
   `RateLimitedZerodhaClient` (1 per 200ms internal queue + 5-sec timeout +
   circuit-breaker that opens after 3 consecutive failures).

7. **Multi-user-aware schema from day 1.** Every table has `user_id`.
   Every method takes `userId`. Phase 1-3 hardcodes to single
   `primary_trader_user_id`; Phase 5 activates real multi-user without
   schema changes.

8. **Emergency pause.** Setting env var `STOKR_V5_TRADING_PAUSED=true`
   makes the scanner skip all scans and `enterTrade()` reject all
   requests. Exit monitor keeps watching open positions. You set
   this in 5 seconds without redeploying.

9. **Restart-safe.** On boot during market hours, load all OPEN
   `live_trade` rows; `DynamicExitMonitor` resumes monitoring them
   immediately. We never lose track of an open trade across a restart.

10. **Friday-flat check.** Saturday 06:00 IST job verifies broker is
    flat at market close. If not → loud Telegram alert.

11. **MIS session close.** At 15:15 IST, `DynamicExitMonitor` closes
    all open trades cleanly. We never leave anything for Zerodha's
    3:20 PM auto-squeeze (which can fill at terrible prices).

12. **All admin actions are audited.** Every dashboard button click
    writes to `admin_actions` with user/action/payload/timestamp.

---

## 7. Strategy logic (ORB only in Phase 1-3)

**Entry rules:**
- Evaluate only between 09:30 and 14:30 IST
- Compute opening range from 09:15–09:30 (15 1-min bars)
- ORH = max(high), ORL = min(low), OR_avg_vol = avg(volume)
- For each 1-min bar after 09:30:
  - If close > ORH AND volume >= 1.5 × OR_avg_vol → LONG signal
  - If close < ORL AND volume >= 1.5 × OR_avg_vol → SHORT signal
  - One signal per symbol per day (cooldown 6h)
- Stock price must be ₹200–₹3,000
- Risk per trade: `risk = abs(entry - stop)` where `stop = opposite OR edge, capped at 0.6%`
- Skip if risk/entry < 0.0008 (too-tight stop = noise)
- Target = entry + 3R (so logical target visible, but real exit is dynamic)
- Quantity = `floor(₹5,000 / entry_price)`

**Exit rules (in order of priority):**
1. Logical stop hit → exit MARKET
2. Trailing stop hit (only armed after MFE >= +0.5R; trail dist = 0.3R) → exit MARKET
3. Breakeven stop (armed after MFE >= +0.3R; moves stop to entry) → exit MARKET
4. Logical target hit → exit MARKET
5. Session close (>= 15:15 IST) → exit MARKET
6. Manual close from dashboard → exit MARKET

The disaster stop at broker (-0.8% from entry) is purely a safety net
for app crashes. It will rarely fire. If it does fire, that's a loud
alert because it means our app missed the dynamic exit.

**Paper mode (Phase 1) realistic slippage:**
- BUY fills at next bar's HIGH (worst case)
- SELL fills at next bar's LOW (worst case)
- This is intentionally pessimistic so live results are not worse than paper.

---

## 8. Kill switches (per-user, configurable from dashboard)

| Trigger | Action |
|---|---|
| Daily loss >= ₹1,000 (configurable) | Halt new entries for the day; keep monitoring open trades |
| 4 consecutive losing trades | Halt new entries for the day |
| Broker disconnected mid-session | Halt new entries; keep watching open trades (disaster stops protect) |
| `STOKR_V5_TRADING_PAUSED=true` | Halt new entries; keep watching open trades |
| Dashboard [HALT TRADING] button | Cancel pending; close all open at MARKET; disable strategy |
| Circuit breaker open (3 broker failures) | Halt new entries for 30s, retry |

---

## 9. Multi-user (architecturally ready, activated in Phase 5)

**Phase 1-3 behavior:** every method takes `userId`, every DB row has
`user_id`, but we hardcode to the single `primary_trader_user_id` from
config. The system behaves exactly like a single-user system, but the
plumbing is multi-user-ready.

**Phase 5 activation (when there are real users):**
- Onboarding flow: user logs in → connects their own Zerodha → enables ORB
- Each user has independent: kill switches, daily loss limit, strategy on/off
- One ORB scan per minute (not per user × symbols — we scan once and check
  each user's enable flag)
- Signal fan-out: signal generated → for each enabled user → `enterTrade(userId, ...)`
- Failure isolation: user 3's Zerodha rejection does not stop users 1, 2, 4
- Per-user dashboard view + admin overview (all users) page
- Admin role vs trader role enforced at controller level

**Why we don't fully build it now:** retrofitting multi-user later is
painful (every query needs WHERE user_id). Architecting for it now is
cheap (~150 extra lines). Activating it requires onboarding UI + per-user
config UI, which is ~1,000 lines we don't need yet.

---

## 10. The admin dashboard (~600 lines React, 1 page)

`/admin/v5-dashboard` — single page, auto-refreshes every 2 seconds.

### Layout sections (top to bottom)

**A. Status header (always visible at top)**
- Big colored dot per system: broker / strategy / feed / loss-limit
- Red dot = click for details
- Big red [HALT TRADING] button on right

**B. Live status tiles (4 in a row)**
- Broker: ZERODHA connected, token expires in 14h 23m
- Strategy: ORB live, last scan 12s ago, 8 signals today
- Market data: live feed, lag 2s
- Daily loss: ₹245 of ₹1,000 (24% bar)

**C. Open trades table (live updating)**
- Columns: symbol, side, qty, entry, current, SL, target, MFE, P&L, [Close]
- Each row's P&L green/red
- [Close Now] button per row

**D. Strategy controls (admin only)**
- [Enable ORB] / [Disable ORB] toggle
- Daily loss limit input (editable)
- Consecutive losses kill switch input (editable)
- [Run Backtest] link to existing backtest page

**E. Live equity curve (chart)**
- X axis: today's market hours
- Y axis: cumulative P&L
- Vertical lines: each trade entry/exit
- Horizontal red line: daily loss limit (so you see how close you are)

**F. Today summary**
- Trades: 7 (3 wins, 2 losses, 2 open)
- Win rate: 60% (excluding open)
- Best trade, worst trade
- Net P&L (today): +₹103

**G. Per-trade timeline (collapsible)**
- Click any closed trade → expand
- Entry time, price, reason
- MFE reached at what time
- Each exit decision evaluated (which rule fired, why)
- Exit fill price, P&L
- Time series of price vs SL/target during trade

**H. Real-time alerts feed (last 50)**
- Color: info / warning / error
- Auto-scrolling
- Examples:
  - "14:23  [info]  ORB signal RELIANCE BUY @2456 → entered, qty 2"
  - "14:25  [info]  Trade #5 trailing stop hit @2461, P&L +₹10"
  - "14:31  [warn]  Broker timeout, retry 1/3"
  - "14:32  [error] Disaster stop fired on HDFC — INVESTIGATE NOW"

**I. Recent admin actions log**
- Last 20 dashboard actions (you halted, you closed, you toggled, etc.)
- For audit and "what did I do?" recall

---

## 11. Critical alerts (via existing Telegram service)

Sent only for actions that need your attention immediately:
- Token expires in < 2 hours during market hours
- Disaster stop fired on any trade
- Daily loss limit hit
- Consecutive-loss kill switch fired
- Broker disconnected > 30 seconds during market hours
- Friday-flat check failed (orphan over weekend)
- App restarted with open trades (you should know)

Routine events (each trade entry/exit) do NOT go to Telegram — that
would be noise. They're in the dashboard alerts feed only.

---

## 12. Phased delivery (with explicit go/no-go gates)

I will not advance to the next phase without your explicit "go." Each
phase ends with a verification checklist YOU run.

### Phase 1 — Skeleton, paper-only, dashboard live (1 session)
**Build:**
- Module `stokr-v5` with the 12 classes
- DB migration for 3 tables
- React dashboard page
- All wired up but `STOKR_V5_LIVE_ENABLED=false` → paper trading only
- Realistic-slippage paper simulator

**Verification (you):**
- [ ] Dashboard loads at `/admin/v5-dashboard`
- [ ] Status tiles show correct broker connection state
- [ ] During market hours, you see ORB signals appearing
- [ ] Paper trades show correctly in open trades table
- [ ] Exits trigger correctly (you'll see MFE update, exit reason logged)
- [ ] Equity curve renders
- [ ] [HALT TRADING] button works
- [ ] No errors in app log

**Go-no-go gate before Phase 2:** all checkboxes pass over one full
trading session.

### Phase 2 — Live execution + disaster stop (1 session)
**Build:**
- Wire real Zerodha entry MARKET placement (was paper-simulated)
- Disaster stop SL-M placement after entry fill
- Dynamic exit places real broker MARKET (cancels disaster first)
- Pre-trade margin check
- Partial fill handling
- Kill switch wiring (daily loss, consecutive losses, dashboard halt)
- Old strategies forced to PAPER mode via DB update

**Verification (you):**
- [ ] One live trade with qty=1 — entry fills, disaster stop appears
- [ ] Exit triggers — disaster stop cancelled, MARKET sell fills
- [ ] No orphans (broker positions exactly match `live_trade` table)
- [ ] Dashboard reflects everything in real time

**Go-no-go gate:** one full day of clean live trading at qty=1.

### Phase 3 — Hardening + auto-heal (1 session)
**Build:**
- Restart safety (resume from OPEN trades on boot)
- Broker disconnect detection → auto-pause new entries
- Token-expiry alerts before market open
- Circuit breaker on broker calls
- Friday-flat check
- MIS session-close at 15:15
- Telegram alerts for critical events
- Orphan auto-square-off integrated with v5

**Verification (you):**
- [ ] Restart app mid-session → open trades resume monitoring
- [ ] Kill broker connection → new entries blocked, open trades still tracked
- [ ] Telegram alerts arrive for token-near-expiry test
- [ ] 15:15 session-close test → trades cleanly closed

**Go-no-go gate:** two weeks of clean live trading.

### Phase 4 — (optional, much later) Delete old code
- Catalog scanner, smart-exit, position sweeper, BOTH mode, 16 unused
  strategies, execution simulator
- Frees ~30,000 lines from production
- Only after Phase 3 has been clean for 2 weeks

### Phase 5 — (optional, when you have users) Multi-user activation
- Onboarding flow: per-user Zerodha connection
- Per-user config pages
- Signal fan-out
- Admin-overview vs trader-self dashboard views
- ~1,000 lines, no schema changes needed

---

## 13. What I am NOT promising

I want this in writing so I never quietly drift from it later:

- No "20%/month" claims. Realistic with ORB on ₹75k: small profits in
  trend months, small losses in chop months, broadly break-even after
  costs in a typical month.
- No claim that v5 is "bug free." It's _designed_ to be debuggable, not
  bug-free. The promise is that bugs will be in 1,300 lines that you and
  I can read together, not 60,000 lines that nobody can.
- No claim that v5 is "complete." Phase 5 features (real multi-user
  activation, etc.) come later if needed.
- No claim that the strategies will improve. ORB is what it is. v5
  fixes the _execution_ problem, not the _edge_ problem.

---

## 14. What I need from you before starting Phase 1

1. **Approval of this plan as the source of truth.** Once approved I
   refuse to deviate without explicit "change X" from you.
2. **Confirmation that paper-first is acceptable** for Phase 1 (we don't
   touch live capital until Phase 2 verification passes).
3. **Confirmation that the old code stays for now.** No deletions in
   Phase 1-3. Phase 4 (deletion) is a separate "yes" later.

When you say "start Phase 1" I begin building. Until then, nothing in
the live trading path moves.

---

## Appendix A — Things I considered and decided not to add

| Considered | Why no |
|---|---|
| Real-time SMS alerts | Telegram covers it, SMS costs ₹0.30/msg |
| OAuth for dashboard | Single admin, basic auth is fine |
| Append-only audit log DB | Overkill for now |
| Multi-strategy support (Phase 1-3) | ORB only first, prove the pipeline |
| Historical data warehouse | Existing system has one; reuse |
| Volatility-weighted position sizing | Fixed ₹5k is fine for ₹75k capital |
| WebSocket dashboard updates | 2-sec polling is enough; simpler |
| Order book depth analysis | ORB doesn't use it |
| Mobile app | Dashboard is mobile-responsive |
| ML / pattern matching | Existing KNN code didn't help |
| Multi-broker support | Zerodha only; one thing done well |
| Auto-tuning of parameters | Manual changes via dashboard; we know what we're doing |

## Appendix B — Existing assets we reuse

| Asset | How v5 uses it |
|---|---|
| `TelegramDeliveryService` | Critical alerts |
| `ZerodhaKiteApiClient` | Wrapped by `RateLimitedZerodhaClient` |
| Market data ingestion | Read-only consumer |
| User/auth tables | Same `users` table for multi-user phase |
| React + Vite UI | One new page added |
| `OrphanPositionSquareOffService` | Kept and watches v5 trades too |
| Backtest engine | Used for backtesting v5 ORB |
| Postgres + Flyway migrations | One new migration only |
