# LIVE_TRADING_FAILURE_CHAIN_PROOF.md
**Section 8 — Failure Chain Proof Matrix + Final Verdict** · READ-ONLY · evidence only

## Hypothesised chain
`Missing Exit Legs → Internal Positions Remain Open → Ghost Positions Accumulate → Max Open Position Guard Triggers → New Live Orders Rejected → Zerodha Never Receives Executable Orders → 0 Live Fills`

## Per-link proof matrix
| # | Link | Evidence | Confidence |
|---|------|----------|-----------|
| 1 | **Missing exit legs** | `SignalOutcomeExitService` only places exits for entries in FILLED/PARTIALLY_FILLED/ACCEPTED (line 207); event listener is in-memory & lost on restart (68-70); backfill capped 20/run/7d (82). `PositionSweeperService.sweepMissingExitLegs` exists and fired 2026-06-10 (`POSITION_SWEEP: signal terminated but no exit leg created` — HCLTECH/SBIN/ICICIBANK). | **HIGH** |
| 2 | **Internal positions remain open** | `portfolio_positions` qty≠0: 1 live `deleted=false` + 72 live `deleted=true` + 3 sim. Duplicate un-netted rows per symbol (COALINDIA ×4, BAJFINANCE ×3). | **HIGH** (positions persist) / **LOW** (causal link to #1 — `portfolio_positions` writer not traced; table has no order/signal linkage) |
| 3 | **Ghost positions accumulate** | All 73 live rows broker-unconfirmed: 0 real ZERODHA fills, `broker_position_observations`=0, runtime `GHOST_INTERNAL_POSITION broker=0 internal=±1`. | **HIGH** (they are ghosts) / **LOW** (origin path unproven) |
| 4 | **Max open position guard triggers** | `MaxOpenPositionsRule` counts `findByUserIdAndDeletedFalse`, qty≠0, **no is_simulation / no broker filter**; limit `STOKR_RISK_MAX_OPEN_POSITIONS=5`. Rejected ZERODHA "Max open positions reached (5/5)": **750 on 2026-05-26, 1 on 05-27, ~0 since** (June=0). | **HIGH that it fired (2026-05-26)** / **DISPROVEN as current mechanism** (now counts 1; June "5/5"=0) |
| 5 | **New live orders rejected** | ZERODHA REJECTED dominates. **But reason has shifted**: 05-26 = `Max open positions` (750). After 05-26 = `active order already exists` (71; 68 June), `Trader account not found` (24), heartbeat (3), broker mismatch (2), halt (2). The **current** blocker is `DuplicateActiveOrderRule` (paper leg vetoes live leg — vendor-blind dedup), NOT the position guard. | **HIGH** (orders rejected) / link-to-#4 only **HIGH for 2026-05-26** |
| 6 | **Zerodha never receives executable orders** | No ZERODHA order ever in SUBMITTED/ACCEPTED/FILLED (deleted=false: REJECTED 99/CANCELLED 16/FAILED 6). All terminate at RISK_CHECK/gate **before** `ExecutionService.dispatch`. No numeric broker order id (22 exec ids are synthetic UUIDv3 on CANCELLED orders). | **HIGH** |
| 7 | **0 live fills** | `oms_orders` ZERODHA FILLED+PARTIALLY_FILLED = **0 / 891** (2026-05-25→06-10), holds with/without `deleted`. 22 ZERODHA executions are synthetic/cancelled, not market fills. | **HIGH** |

## What is actually happening (two independent blockers, not one chain)
- **Blocker A — ghost/position-limit (links 1→4):** real, but a **2026-05-26 event** (750 same-day rejects). Ghosts since soft-deleted; guard now reads 1/5. Currently **dormant**.
- **Blocker B — BOTH-mode dual-vendor dedup collision:** PAPER order persisted first → vendor-blind `DuplicateActiveOrderRule` (@Order 36, before MaxOpenPositions @37) rejects the LIVE twin "active order already exists." This is the **current dominant** live-reject cause (68 June) and is **not in the hypothesised chain**.
- **Blocker C — secondary gates:** `Trader account not found` (24), heartbeat (3), broker mismatch (2), operational halt (2).
- **Common outcome:** 0 real Zerodha fills; only SIM fills (49 orders / 260 executions). Platform behaves as paper-only.

## FINAL QUESTION
> "The platform is functioning as a paper-trading system because internal ghost positions and position-limit guards prevent live Zerodha execution."

### Verdict: **PARTIALLY PROVEN**

**Proven (HIGH):**
- *"Functioning as a paper-trading system"* — 0 live fills / 891 ZERODHA attempts; all real fills are SIM. (`ZERODHA_FILL_VALIDATION.md`)
- *Ghost positions exist and the position-limit guard counts internal/unreconciled positions* — code + 750 rejects on 2026-05-26. (`POSITION_LIMIT_GUARD_TRACE.md`, `GHOST_POSITION_FORENSICS.md`)
- *Orders never reach the broker* — terminate pre-dispatch. (`ZERODHA_ORDER_LIFECYCLE_TRACE.md`)

**Not proven / contradicted (the "because" clause):**
- The cited cause — *ghost positions + position-limit guard* — was operative for **one day (2026-05-26, 750 rejects)**, not the sustained/current cause. Since 2026-05-27 the position guard has rejected ≈1 live order; June = 0.
- The **current** cause of 0 live fills is a **different, independent** defect: the BOTH-mode vendor-blind `DuplicateActiveOrderRule` collision (paper leg blocks live leg, 68 June rejects), plus `Trader account not found` (24). Neither involves ghost positions or the position-limit guard.
- Links #2/#3 causal origin (how internal live positions are created given 0 real fills) is **unproven** — `portfolio_positions` writer not traced; table carries no order/signal/execution linkage.

**Net:** the *outcome* (paper-only, 0 live fills) is PROVEN; the *specific causal mechanism asserted* is PROVEN only for 2026-05-26 and is **not** the operative cause today. Hence **PARTIALLY PROVEN**.

---
*Companion reports:* EXIT_LEG_OWNERSHIP_TRACE · GHOST_POSITION_FORENSICS · POSITION_LIMIT_GUARD_TRACE · ACTIVE_ORDER_GUARD_TRACE · DUAL_VENDOR_EXECUTION_ANALYSIS · ZERODHA_ORDER_LIFECYCLE_TRACE · ZERODHA_FILL_VALIDATION.
*No code changed. No fixes applied. Read-only forensic pass.*
