# DUAL_VENDOR_EXECUTION_ANALYSIS.md
**Section 5 — Dual Vendor Execution Analysis** · READ-ONLY

## Path
`OrderIntentProcessor.processSignalIntent()` → mode `BOTH` → `dispatchBothMode()`
(`stokr-execution/src/main/java/com/stokr/execution/pipeline/OrderIntentProcessor.java`, lines 206-210, 635-749).

## Creation sequence (the defect)
Inside `dispatchBothMode` (line numbers exact):
1. **PAPER order created FIRST** (648-650):
   `paperDraft = buildDraftFromSignal(signal, PAPER…)` → vendor `SIM` (buildDraft line 405); `createOrGetIdempotent(key+":PAPER")` → persisted in state **`CREATED`**.
2. **LIVE order created SECOND** (659-665): vendor `ZERODHA` (line 402-403); `liveDraft.setSignalId(null)` (663); `createOrGetIdempotent(key+":LIVE")` → state `CREATED`.
3. Orders paired (673-678) via `LinkedExecutionService.linkOrdersForSynchronizedExecution`.
4. **LIVE dispatched first** (689-731): pre-flight, then `advanceOrderForDispatch(liveOrder, LIVE)`.
5. `advanceOrderForDispatch` (751-807): `CREATED → VALIDATED → RISK_CHECK`, then `riskEngineService.evaluate(ctx)`.

## Shared state / shared dedup check
At step 5 the LIVE order's risk evaluation runs `DuplicateActiveOrderRule` (@Order 36). That rule's query (`countActiveSameDirection`, symbol+side+user, **no vendor filter** — see `ACTIVE_ORDER_GUARD_TRACE.md`) counts the **PAPER order created in step 1**, which is still in state `CREATED` (∈ DUPLICATE_STATES). The LIVE order's own id is excluded, the PAPER id is not.
→ `n = 1 > 0` → **LIVE REJECTED: "An active order already exists for this symbol and side"** — *before* `MaxOpenPositionsRule` (@Order 37) is even reached.

The LIVE leg also has `signalId = null` (set line 663), so the rule's EXIT-bypass (`if o.getSignalId() != null`) is skipped; it proceeds straight to the count.

## Intended vs actual
- **Intended** (`LinkedExecutionService` Javadoc, lines 17-19, 30-33): "LIVE → PAPER synchronization… PAPER executes ONLY if LIVE succeeds." LIVE is meant to gate PAPER.
- **Actual**: PAPER is **persisted before** LIVE is risk-checked, and the dedup guard is vendor-blind, so the PAPER row *vetoes its own LIVE twin*. The gating is inverted by a side effect of creation ordering + guard scope.

## Can paper execution prevent live execution? — YES (proof)
- **Code**: PAPER persisted (CREATED) at line 649 → LIVE risk-checked at 757 → DuplicateActiveOrderRule counts PAPER (vendor-blind) → LIVE rejected.
- **Runtime (2026-06-10)**: 8/8 signals — SIM leg created, ZERODHA leg REJECTED "active order already exists" ~1 s later (HCLTECH 09:34:07/08, SBIN 09:54:12/13, ICICIBANK/KOTAKBANK/JSWSTEEL/ADANIPORTS 11:44, HDFCBANK/BAJFINANCE 14:03). SIM legs then FILLED (2) or swept (3) or cluster-rejected (3); ZERODHA legs: 0 filled.
- **History**: 68 June "active order already exists" ZERODHA rejects.

## Timing difference
SIM-create → ZERODHA-risk-check occur within the **same `@Transactional` `dispatchBothMode` call** (sub-second); DB shows the twins 0–1 s apart.

## Confidence: **HIGH** — code ordering + guard scope + matched runtime pairs.
