# ZERODHA_ORDER_LIFECYCLE_TRACE.md
**Section 6 — Zerodha Fill Path** · READ-ONLY

## Intended lifecycle
`Signal → OrderIntentProcessor → OrderLifecycleService(state machine) → RiskEngine → ExecutionService.dispatch → broker adapter (ZERODHA) → broker ACK → broker fill → oms_executions/portfolio_positions`

## Actual state progression of a LIVE leg (BOTH mode)
`dispatchBothMode` → `advanceOrderForDispatch(liveOrder, LIVE)` (`OrderIntentProcessor.java:751-807`):
```
CREATED → VALIDATED → RISK_CHECK → [riskEngineService.evaluate] → REJECTED
```
Dispatch to the broker only happens **after** a clean risk pass and `PENDING_SUBMISSION` (lines 724-730 / 313-366: `executionService.dispatch(... "ZERODHA" ...)`).

## Where orders stop — precisely
**Stop point #1 (current, dominant): RISK_CHECK → REJECTED by `DuplicateActiveOrderRule` (@Order 36).**
The paired PAPER leg (created first) trips the vendor-blind dedup → LIVE rejected before dispatch. The `executionService.dispatch(...ZERODHA...)` call on line 726 is never reached. June: 68 rejects.

**Stop point #2 (2026-05-26 only): RISK_CHECK → REJECTED by `MaxOpenPositionsRule` (@Order 37)** — 750 rejects that day (ghost positions ≥5). Also before dispatch.

**Stop point #3: BROKER_TRUTH gate** — even if risk passes, `advanceOrderForDispatch` (787-805) / `processSignalIntent` (315-334) run `brokerPositionTruthService.validateForExecution(...)`; violations → REJECTED (`phase=BROKER_TRUTH`). Still before broker send.

**Stop point #4: secondary gates** seen in data — `Trader account not found` (24, June), `No healthy LIVE strategy runtime (heartbeat)` (3), `Execution blocked — broker mismatch` (2), `Broker operational halt` (2). These reject at the eligibility/safety gate (`OrderIntentProcessor` LIVE-eligibility lines 170-194, `OmsSafetyGateService`), again pre-dispatch.

## Can a valid order reach OrderPlacement → Execution → Broker API → ACK → Fill today?
**No.** Every LIVE/ZERODHA order is terminated at a **risk or gate stage that runs before `ExecutionService.dispatch`**. Evidence the broker API is never reached:
- `oms_orders.broker_order_id` for live legs is **null/synthetic** — the 22 ZERODHA-vendor executions carry **UUID** broker ids (`3b06ed75-67d0-375d-…`, name-hash UUIDv3), **not** numeric Zerodha order numbers; all on `CANCELLED` orders, `is_simulation=false` but synthetic.
- ZERODHA orders never occupy `SUBMITTED`/`ACCEPTED`/`FILLED`/`PARTIALLY_FILLED` (deleted=false): only `REJECTED 99, CANCELLED 16, FAILED 6`.

## Distinct ZERODHA users
2 users have ZERODHA orders; both exhibit the same pre-dispatch termination.

## Confidence
- Orders stop at a pre-dispatch risk/gate stage: **HIGH** (state data: no order ever in SUBMITTED/ACCEPTED/FILLED).
- They never reach the real broker API: **HIGH** (no numeric broker order id; 0 fills).
