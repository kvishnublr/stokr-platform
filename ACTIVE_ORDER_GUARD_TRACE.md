# ACTIVE_ORDER_GUARD_TRACE.md
**Section 4 — Active Order Duplicate Guard** · READ-ONLY

## Location
`stokr-risk/src/main/java/com/stokr/risk/rules/DuplicateActiveOrderRule.java`
- `RiskRule` bean, **`@Order(36)`** — runs **before** `MaxOpenPositionsRule` (@Order 37).
- Message (line 79): `"An active order already exists for this symbol and side"`.

## Scope of duplicate detection
`evaluate()` (lines 47-81) calls:
```java
long n = omsOrderRepository.countActiveSameDirection(
        context.userId(), o.getSymbol(), o.getSide(), o.getId(), DUPLICATE_STATES);
if (n > 0) reject("An active order already exists for this symbol and side");
```
Query (`OmsOrderRepository.java:91-102`):
```sql
select count(o) from OmsOrder o
where o.userId = :userId and o.symbol = :symbol and o.side = :side
  and o.deleted = false and o.backtestRunId is null
  and o.id <> :excludeId and o.state in :states
```
`DUPLICATE_STATES` (lines 28-36) = `CREATED, VALIDATED, RISK_CHECK, PENDING_SUBMISSION, SUBMITTED, ACCEPTED, PARTIALLY_FILLED`.

## Scoped by?
| Dimension | In scope? |
|-----------|-----------|
| Symbol | ✅ |
| Side | ✅ |
| User | ✅ |
| Order state ∈ active set | ✅ |
| **Vendor (SIM vs ZERODHA)** | ❌ **NOT** — no `broker_vendor` predicate |
| **Account / executionMode (PAPER vs LIVE)** | ❌ **NOT** — no `execution_mode` predicate |
| **is_simulation** | ❌ **NOT** |

→ **Scope = Symbol + Side (+ User + active state). It is vendor-blind, mode-blind, account-blind.**

## Bypasses (do not apply to entries)
- `backtestRunId != null` → ok (line 49).
- `isExitBypass`: `strategyKey` starts `TERMINAL_`, or idempotencyKey starts `outcome-exit:` / `terminal:flatten:` / `terminal:exit:` (lines 84-100).
- Signal is `SignalType.EXIT` (lines 61-69).
Entry orders (BUY/SELL) match none of these.

## Does `SIM BUY SBIN` block `ZERODHA BUY SBIN` from the same signal? — YES
Because the query matches on `symbol='SBIN' AND side='BUY'` for the user with the SIM order in any `DUPLICATE_STATES` state (e.g. `CREATED`), and there is **no vendor filter**, a SIM order in `CREATED` is counted when the ZERODHA order is risk-checked → `n>0` → ZERODHA REJECTED. See `DUAL_VENDOR_EXECUTION_ANALYSIS.md` for the exact creation order proving the SIM leg pre-exists.

## DB evidence
ZERODHA REJECTED with this exact reason: **71 lifetime / 68 in June 2026** (dominant current live-reject reason). On 2026-06-10, all 8 ZERODHA legs rejected with it, each ~1 s after its SIM twin (e.g. HCLTECH SIM `CANCELLED` 09:34:07 → ZERODHA `REJECTED` 09:34:08).

## Confidence
- Scope = symbol+side, vendor-blind: **HIGH** (JPQL).
- SIM leg blocks ZERODHA leg: **HIGH** (code + 68 June rejects + 1-second pairing).
