# POSITION_LIMIT_GUARD_TRACE.md
**Section 3 — Position Limit Guard Validation** · READ-ONLY

## Location
`stokr-risk/src/main/java/com/stokr/risk/rules/MaxOpenPositionsRule.java`
- Service: a `RiskRule` bean, `@Order(37)`, code `MAX_OPEN_POSITIONS`.
- Method: `evaluate(RiskContext)` (lines 31-57).
- Message builder (lines 51-53): `"Max open positions reached (" + open + "/" + maxOpenPositions + ")"`; or `"Pilot: one stock at a time…"` when `maxOpenPositions <= 1`.
- Limit: `@Value("${stokr.risk.max-open-positions:100}")`. **Production env: `STOKR_RISK_MAX_OPEN_POSITIONS=5`** (confirmed from `stokr-api` container env). → the "5/5" string.

## Counting logic (lines 41-44)
```java
List<PortfolioPosition> list =
    portfolioPositionRepository.findByUserIdAndDeletedFalse(context.userId());
long open = list.stream()
    .filter(p -> p.getQuantity() != null && p.getQuantity().abs().compareTo(ZERO) > 0)
    .count();
```
Repository method (`PortfolioPositionRepository.java:14`):
`List<PortfolioPosition> findByUserIdAndDeletedFalse(UUID userId);` — filters **only** `user_id` + `deleted = false`.

## Does it count… ?
| Candidate | Counted? | Evidence |
|-----------|----------|----------|
| **Internal positions** | ✅ YES | counts every `portfolio_positions` row, qty≠0 |
| **Simulation/paper positions** | ✅ YES (not excluded) | query has **no `is_simulation` filter**; sibling methods `findAllRealOpenPositions`/`findRealByStrategyKeyAndDeletedFalse` (which add `simulation = false`) exist but are **not** used here |
| **Broker positions** | ❌ NO | never queries broker truth / `broker_position_observations` |
| **Reconciled positions** | ❌ NO | no reconciliation check |
| **Open executions** | ❌ NO | counts positions, not `oms_executions` |
| Soft-deleted positions | ❌ NO | `deleted = false` excludes them |
| LIVE/PAPER order being evaluated | partial | PAPER **orders** bypass the rule entirely (lines 37-40); test trades bypass (line 33). But the **count** still includes sim *positions*. |

## What contributes to "5 / 5"
Any `portfolio_positions` row with `deleted=false` AND `quantity≠0` for the user, **regardless of simulation flag or broker confirmation**. The guard is satisfied (rejects) once ≥5 such rows exist.

## DB evidence — when it actually fired
ZERODHA orders rejected with `Max open positions reached (5/5)`:
| Date | Count |
|------|-------|
| 2026-05-26 | **750** |
| 2026-05-27 | 1 |
| after 05-27 | **0** (1 stray "5/5" total post-05-26; June = 0) |

Current `portfolio_positions` (qty≠0):
| is_simulation | deleted | open rows |
|---|---|---|
| false | **false** | **1** (BANDHANBNK, S7_RANGE_FADE, 06-05) |
| false | true | 72 |
| true | true | 3 |

→ The guard **currently counts 1**, well under 5. The 750 same-day rejections on 2026-05-26 prove the guard *did* saturate when ≥5 live positions were `deleted=false`; those have since been soft-deleted (72 rows now `deleted=true`, batch-deleted 05-26/05-08/etc.).

## Confidence
- Counting logic counts internal + simulation, ignores broker/reconciliation: **HIGH** (code).
- Guard saturated and blocked live on 2026-05-26: **HIGH** (750 rejects).
- Guard is the *current* blocker: **LOW / DISPROVEN** (1 open now; 0 June "5/5" rejects).
