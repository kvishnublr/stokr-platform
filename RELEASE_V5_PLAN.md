# Release v5 — Honest analysis + simplification plan

## Where we are today (measured, not guessed)

- **1,038 Java files** across 13 modules
- **556 Spring beans** (services / components / repositories / controllers)
- **64 scheduled tasks** all competing for the same data
- **460 SQL migrations**
- **17 strategy generators**, of which we actually use 1 (ORB) for live
- **57 backtest files** that production live trading never touches

To place ONE order, we go through 6 services and ~78 injected dependencies:
  CatalogDrivenScanScheduler → OrderIntentProcessor (808 lines, 23 deps) →
  ExecutionService → ExecutionSimulator (466 lines, 22 deps) →
  OrderPlacementService → OrderLifecycleService

To close ONE position, 7 services with 33 deps and ~2,250 lines.

**This is why every change breaks something.** Each layer added defensive logic
because it didn't trust the layer below. Now the layers fight each other.
The bugs we've hit (orphan positions, TIME_EXIT with no exit order, cluster
rule eating ORB, silent sizing fallback) are all symptoms of this.

## What I will NOT do

- Pretend I can rewrite 1,038 files in this session.
- Touch the existing live-trading code path while you're flat and safe.
- Recommend "delete everything and start fresh" — you'd lose months of
  legitimate work (broker integration, auth, market data, UI, backtest).
- Add a clever new strategy promising 20%/month. Doesn't exist.

## What Release_v5 actually is

A **parallel slim trading path** that lives alongside the existing code,
takes over the live path completely, and lets the old code rust.

  Existing complex pipeline  →  KEPT, frozen, used for paper-only backtest
  New minimal live pipeline   →  ALL live orders go through this

When the slim path is proven, the old live-trading services can be deleted
module-by-module. **Or never deleted** — that's fine too, because they no
longer run for live trading.

## Architecture: the slim path (HYBRID — confirmed 2026-06-15)

User confirmed: **Dynamic exits in our code + broker disaster stop as safety net.**
The broker is purely insurance against an app crash. All real exit decisions
(trailing, breakeven, volume-based) happen in our 700 lines.

```
  [MinimalSignalScanner @Scheduled 1m]
      │  evaluates ORB on 20 symbols at 9:30 IST
      │  ONE entry per symbol per day
      ▼
  [MinimalOrderService.enterTrade]
      │  1. place Zerodha entry MARKET
      │  2. on fill: place WIDE disaster stop SL-M at -0.8% (insurance only)
      │  3. write ONE DB row: live_trade(id, symbol, side, qty, entry_price,
      │       logical_sl, logical_target, disaster_stop_order_id, mfe, status)
      ▼
  [DynamicExitMonitor @Scheduled 5s — per-trade in-app]
      │  for each OPEN live_trade:
      │  - read current price
      │  - update MFE (high water mark)
      │  - decide: hit logical SL? hit logical target?
      │             trail-stop triggered? breakeven move?
      │             order-flow reversal? time exit?
      │  - if exit decision:
      │     a. cancel disaster_stop_order_id at broker
      │     b. place MARKET sell to flatten now
      │     c. mark trade CLOSED, record realized P&L
      ▼
  [DisasterStopFiredHandler — broker poll every 1m]
      │  if disaster stop fills first (our app was down):
      │  - record trade CLOSED with realized P&L from fill
      │  - alert admin (this should never happen — investigate)
```

**The safety chain:**
1. Normal: our app decides exits in <5s. Disaster stop never fires.
2. Our app slow / network blip: our exit fires within seconds of recovery.
3. Our app fully crashes: disaster stop at broker fires at -0.8%. Position protected.
4. Zerodha down: nothing we can do anyway. Same as everyone.

**Six classes. One DB table. ~700 lines.** No catalog scanner, no BOTH mode,
no cluster rule, no smart-exit engine, no position sweeper, no execution simulator.

## Why this fixes the bugs at the root

| Bug we saw | Why it can't happen in v5 |
|---|---|
| Orphan positions (TX rollback after broker submit) | Broker call is the *last* step in its own non-transactional method. Nothing after it can roll back the DB record. |
| TIME_EXIT with no exit order | No time-exit. SL and target sit at the broker. They fill or you carry to MIS auto-square-off. |
| Cluster rule killing ORB | No cluster rule. One trade per symbol per day. |
| Silent sizing fallback | Quantity computed once: `floor(₹5000 / entry_price)`. No enum parser. |
| "Signals fire but no entry order" | Signal IS the entry intent. If `enterTrade()` throws, you see the exception immediately in the dashboard. |
| Sizing mode typo | No sizing modes. One formula. |

## Strategy in v5 — ORB only

ORB rules (already validated in backtest):
- 9:15–9:30 opening range (high, low, avg volume)
- After 9:30 first 1m bar that closes beyond OR with volume >= 1.5x OR-avg
- Entry = breakout close. Stop = opposite OR edge (capped 0.6%).
- Target = 3× risk (broker LIMIT) + emergency stop = stop (broker SL-M)
- ONE trade per symbol per day. Up to 15 concurrent (15 × ₹5k = ₹75k).
- No 11:30–15:00 entries (let trades work).
- Hard daily loss limit ₹1,000 → halt new entries.

Realistic expectation: **+₹2,000–4,000 in good months, −₹500–1,500 in bad
months.** ORB cannot make ₹15k/month. Nothing can on ₹75k cash.

## UI — what we add vs keep

User confirmed: **comprehensive dashboard (status + controls + live chart + per-trade timeline).**
All existing UI pages (login, broker connection, strategy config, trade history,
charts) stay untouched. We add ONE new admin page that becomes the single
"at-a-glance" control center.

### New admin page: `/admin/v5-dashboard` (~1100 lines)

Single page, auto-refreshes every 2 seconds. Sections:

1. **System status tiles** (red/yellow/green at a glance):
   - Broker: connected? token expiry?
   - Strategy: ORB on/off, last scan time, signals today
   - Market data feed: live? lag?
   - Daily loss meter: ₹used / ₹limit, bar visualisation

2. **Open trades table** (live updates):
   - Symbol, side, qty, entry, current price, logical SL, logical target
   - MFE (best so far), trailing stop level, current P&L
   - Action button per trade: [Close Now]

3. **Strategy controls**:
   - [Enable ORB] / [Disable ORB] toggle
   - Daily loss limit input (live editable)
   - [Run Backtest] button → opens existing backtest UI
   - [HALT TRADING] big red button (cancels all open + disables strategy)

4. **Live equity curve** (today only, 5-second resolution):
   - Line chart: cumulative P&L over the trading day
   - Vertical markers for each trade entry/exit
   - Daily loss limit shown as a red horizontal line

5. **Per-trade timeline** (click any closed trade to expand):
   - Entry: time, price, reason
   - MFE reached, when
   - Exit decision: what triggered (trail/target/SL/time/manual)
   - Exit fill: time, price, P&L

6. **Real-time alerts feed** (last 50, auto-scrolling):
   - Color-coded: info / warning / error
   - "Cluster: 3 ORB signals fired same minute — took strongest"
   - "Trade #5 exit failed (retry 1/3): broker timeout"
   - "Disaster stop fired on HDFC — INVESTIGATE"

Backend support for this page is just ONE controller (`V5DashboardController`)
returning a single JSON snapshot. The page polls it. No WebSockets needed.

## Phased delivery (so we don't repeat the "deploy and pray" cycle)

**Phase 1 — Skeleton + paper trading + dashboard**
- Create `stokr-v5` module (sibling to existing modules)
- Build `MinimalSignalScanner` (ORB only, 20 symbols)
- Build `MinimalOrderService` with Zerodha calls but **paper-only** initially
- Build `DynamicExitMonitor` (full dynamic exits, no broker dependency)
- Build `live_trade` table (1 migration)
- Build the comprehensive `/admin/v5-dashboard` page (React) + controller
- Run ORB through it in paper for ONE day before any live capital
- Estimated: 2 focused sessions (was 1 — dashboard is heavier than originally planned)

**Phase 2 — Live execution + disaster stop**
- Wire up real Zerodha entry MARKET placement
- Place wide disaster-stop SL-M at broker after fill
- Wire `DynamicExitMonitor` to:
   • cancel disaster stop on logical exit
   • place MARKET to flatten
   • detect if disaster stop fired (app was down → record + alert)
- Daily-loss kill switch tied to dashboard halt button
- **All 17 old strategies forced to PAPER** so only one live path exists
- Run live with ONE trade at 1 share for a day, monitoring the dashboard
- Estimated: 1 focused session

**Phase 3 — Hardening + auto-heal**
- Orphan auto-square-off (the service we already built, kept and wired
  to the v5 trade table too)
- Broker disconnect detection → auto-pause new entries, keep monitoring
  open trades for exit (broker disaster stop is the backstop)
- Token-expiry alert before market open (email/notification)
- Auto-retry transient broker errors (3 attempts with backoff)
- Reconnection-aware: when broker comes back, resume from current state
- Estimated: 1 focused session

**Phase 4 — (optional, later) Decommission old code**
- Delete catalog scanner, smart-exit, position sweeper, BOTH mode, etc.
- Only after v5 has run live cleanly for 2 weeks
- Frees ~30,000 lines of code from production
- Estimated: 1 session

**Total: 4 focused sessions to a clean live system.**
Not "rewrite everything." Just the live path, replaced.

## What I need from you before starting Phase 1

1. **Confirmation that this is the direction.** (If you'd rather pause and
   put money in an index fund instead, that's still the smarter answer
   given your stated target. Just say so.)
2. **Confirmation that the slim path is OK to add as a new module** without
   deleting the old code yet. This is the lowest-risk path: old stays,
   new gets added, old gets ignored, *eventually* old gets deleted when
   we're confident.
3. **One restriction I will enforce:** Phase 1 ships **paper-only**. Even
   if you tell me to skip ahead, I will not. We've learned the hard way
   that going live without a paper test gets us orphans.

## What I am NOT promising

- No "20%/month" / "always profitable" claims. Anyone making those is lying.
- ORB will still have flat days where it makes nothing.
- The slim system might still find a bug — but the bug will be in 600
  lines, not 60,000, so we can actually fix it.

## My honest recommendation, restated

This plan is a real fix to the *engineering* problem. It does not fix the
*business* problem: intraday trading ₹75k cash in Nifty large-caps does
not produce ₹15k/month, no matter how clean the code. If you want that
income, the path is leverage (futures/options — much higher drawdown) or
a different income source entirely.

If you want a **clean, debuggable, admin-friendly system that wins some
days and loses some days at low size**, this plan delivers that.

Say "start Phase 1" and I'll begin.
