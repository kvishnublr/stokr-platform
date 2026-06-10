# EXIT_LEG_OWNERSHIP_TRACE.md
**Section 1 — Exit Leg Validation** · Mode: READ-ONLY (no fixes)

## Owning service
`SignalOutcomeExitService` — `stokr-execution/src/main/java/com/stokr/execution/pipeline/SignalOutcomeExitService.java`
It is the **only** service that *creates* broker exit orders. It calls `OrderPlacementService.place(...)` with idempotency key `outcome-exit:<signalId>:<entryOrderId>:<outcome>`.

A second service, `PositionSweeperService` (`stokr-strategy/.../service/PositionSweeperService.java`), does **not** create exits — it is a janitor that force-**cancels** stranded orders. Its existence is itself evidence that exits go missing (see below).

## What creates entry vs exit orders
| Leg | Creator | Path |
|-----|---------|------|
| **Entry** | `OrderIntentProcessor` | `processSignalIntent()` → `buildDraftFromSignal()` → `OrderLifecycleService.createOrGetIdempotent()`; BOTH mode → `dispatchBothMode()` creates PAPER + LIVE drafts |
| **Exit** | `SignalOutcomeExitService` | (a) event `onSignalOutcome()` `@TransactionalEventListener(AFTER_COMMIT)`; (b) `scheduledBackfill()` every 300 000 ms |
| **Closure (forced)** | `PositionSweeperService` | `@Scheduled` 300 000 ms — sets `OrderState.CANCELLED`, never places an opposing leg |

## When an exit *should* be created
`SignalOutcomeExitService.dispatchForSignal()` (lines 178-220):
1. Signal outcome ∈ `EXIT_OUTCOMES` = {`TARGET_HIT, STOPLOSS_HIT, SL_HIT, BREAKEVEN_EXIT, PRESSURE_EXIT, LIQUIDITY_PROTECTION, FEED_PROTECTION, TIME_EXIT`} (lines 45-48).
2. Entry order resolved via `resolveEntryOrders()` (also pulls `pairedOrderId` for BOTH-mode LIVE legs, lines 225-240).
3. **Entry must be in `FILLED_ENTRY_STATES` = {FILLED, PARTIALLY_FILLED, ACCEPTED}** (line 207). Otherwise `continue` — no exit.
4. `resolveExit()` (283-301): if Zerodha truth shows qty → LIVE/ZERODHA exit; else if entry qty>0 → exit in entry's own mode; else (flat) → `null`, skip.

## Per-strategy answer
Exit creation is **strategy-agnostic** — INDEX_HUNT, ADV_CASH, S7_RANGE_FADE, PRE_OPEN_GAP_OI all flow through the same outcome→`SignalOutcomeExitService` path. The *outcome* is computed upstream by the signal-outcome tracker (emits `signal_outcome` operational event); the exit service reacts identically regardless of strategy. No strategy has its own exit creator.

## CAN a signal terminate without creating an exit? — YES (multiple paths)
1. **Entry never reached FILLED/PARTIALLY_FILLED/ACCEPTED** (line 207). Every LIVE/ZERODHA entry is REJECTED at risk stage (see Sections 4–6), so the LIVE side never has a fillable entry → no LIVE exit is ever generated. (Correct in isolation — nothing to exit — but it means the live book is never managed.)
2. **Lost outcome event** — `onSignalOutcome` is an in-memory `@TransactionalEventListener`; the class comment (lines 68-70) states events are "lost during restarts (in-memory events are not persisted)."
3. **Backfill is bounded** — `scheduledBackfill()` caps at **20 signals per run** (line 82) over a 7-day window (line 77); a burst beyond 20/5 min is not fully covered.
4. **No entry orders** resolved → logs `no_entry_orders`, returns (lines 195-198).
5. **Broker flat** → `resolveExit` returns `null` (line 300).

## DB evidence that exits are in fact missed
`PositionSweeperService.sweepMissingExitLegs()` (lines 117-135) force-cancels FILLED entries whose signal terminated with **no exit leg**, stamping:
`reject_reason = 'POSITION_SWEEP: signal terminated but no exit leg created, closed'`.
Observed on 2026-06-10 for HCLTECH, SBIN, ICICIBANK (SIM legs, CANCELLED). The sweeper firing **proves** the primary exit path left positions unclosed.

## Confidence
- Owner = `SignalOutcomeExitService`: **HIGH** (direct code).
- "Signal can terminate without an exit": **HIGH** (code paths + live sweeper evidence).
