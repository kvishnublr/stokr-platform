# GHOST_POSITION_FORENSICS.md
**Section 2 — Ghost Position Validation** · READ-ONLY

## Scope note
The "73 open live positions" figure was taken **without** the `deleted` filter. Current `portfolio_positions` (qty≠0) decompose as:

| is_simulation | deleted | open rows | Counted by MaxOpenPositionsRule? |
|---|---|---|---|
| false (LIVE) | **false** | **1** | ✅ yes |
| false (LIVE) | true | **72** | ❌ no (soft-deleted) |
| true (SIM) | true | 3 | ❌ no |

So the 73 = **1 active + 72 soft-deleted** live rows. (`findByUserIdAndDeletedFalse` ignores the 72.)

## Linkage limitation (important)
`portfolio_positions` has **no `signal_id`, `order_id`, or `execution_id` column** (schema: id, created_at, updated_at, version, deleted, user_id, symbol, quantity, avg_price, realized_pnl, unrealized_pnl, mtm_price, strategy_key, is_simulation, simulation_run_id, simulation_scenario). Linkage to signal/order/execution can only be **inferred** by `user_id + symbol + strategy_key + time`, not joined. This is itself a forensic gap.

## Broker quantity (truth)
- `oms_orders`: **0 ZERODHA fills / 891** (see `ZERODHA_FILL_VALIDATION.md`).
- `broker_position_observations` (Phase-1 truth table): **0 rows** (freshly deployed today).
- Reconciliation log (runtime): repeated `reconciliation.discrepancy type=GHOST_INTERNAL_POSITION … broker=0 internal=±1` (AXISBANK, DRREDDY, TITAN, CASTROLIND, HDFCLIFE, TATASTEEL, BAJFINANCE, WIPRO).
→ **Broker qty for every live position = 0.** No live position is broker-confirmed.

## Per-position evidence (representative sample, `is_simulation=false`, qty≠0)
| Symbol | Qty | Avg price | Strategy | Entry (updated_at) | Broker qty | Class |
|---|---|---|---|---|---|---|
| BANDHANBNK | 1 | 203.76 | S7_RANGE_FADE | 2026-06-05 (deleted=false) | 0 | Internal only |
| ADANIPORTS | 2 / 1 | 1816.72 / 1817.27 | INDEX_HUNT | 2026-06-05 | 0 | Internal only |
| ASIANPAINT | 1 / 1 / 2 | ~2676–2693 | INDEX_HUNT | 06-05 / 06-08 | 0 | Internal only |
| AXISBANK | 1 / 2 | 1256.40 / 1267.95 | INDEX_HUNT | 06-05 / 06-09 | 0 | Internal only |
| BAJFINANCE | 1 / 1 / 2 | 894–898 | INDEX_HUNT / ADV_CASH | 06-05 / 06-08 | 0 | Internal only |
| COALINDIA | 5 / 4 / 4 / 3 | 464–467 | INDEX_HUNT | 06-05 / 06-08 | 0 | Internal only |
| CASTROLIND | −1 | 184.59 | GAP_FILL | 06-05 | 0 | Internal only |
| DRREDDY | −1 / −1 | 1269–1285 | INDEX_HUNT / GAP_FILL | 06-05 / 06-08 | 0 | Internal only |
*(duplicate rows per symbol are not netted — a position-lifecycle defect; multiple un-closed entries per symbol accumulate.)*

## Classification of all live positions
| Class | Definition | Count |
|---|---|---|
| **Internal only / Unreconciled** | in `portfolio_positions`, broker qty = 0, no real Zerodha fill | **73 / 73** |
| Broker confirmed | matched non-zero broker qty | 0 |
| Unknown | — | 0 |

Because lifetime **real** ZERODHA fills = 0, **no** live position can be broker-confirmed; 100% are internal-only.

## Source (inferred)
Live positions are produced internally despite 0 real broker fills — candidate sources: (a) the 22 synthetic `is_simulation=false` ZERODHA executions on CANCELLED orders, and/or (b) a position-projection path that books positions from non-real fills. The exact `portfolio_positions` writer was **not** traced in this read-only pass → flagged as the one open link (see proof matrix, link 2/3).

## Soft-deletion timeline (72 live ghosts, deleted=true)
updated_at dates: 2026-06-05 (35), 06-08 (32), 06-09 (3), 06-10 (2) — day-boundary batch soft-deletes (sets `deleted=true`, leaves `quantity` intact).

## Confidence
- All live positions are internal-only / broker-unconfirmed: **HIGH** (0 real fills + broker=0).
- Ghosts accumulate intraday then get soft-deleted: **HIGH** (matrix + timeline).
- Exact creation source of live rows: **LOW** (writer not traced; no stored linkage).
