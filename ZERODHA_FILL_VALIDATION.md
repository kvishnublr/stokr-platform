# ZERODHA_FILL_VALIDATION.md
**Section 7 — Historical Validation of "ZERODHA = 0 fills / 891 attempts"** · READ-ONLY

## Data source
Production PostgreSQL `stokr_platform`, table `oms_orders` (and `oms_executions`), host 173.249.55.84, container `stokr-postgres`. Session TZ `Asia/Kolkata`.

## Exact query — attempts & fills
```sql
SELECT count(*) FILTER (WHERE state IN ('FILLED','PARTIALLY_FILLED')) AS zerodha_fills,
       count(*) AS zerodha_total,
       min(created_at)::date AS first, max(created_at)::date AS last
FROM oms_orders
WHERE broker_vendor='ZERODHA';
```
**Result:** `zerodha_fills = 0`, `zerodha_total = 891`, range **2026-05-25 → 2026-06-10**.

- Time range: all history present in the table (no date filter).
- Excluded states: none from the denominator; the numerator counts only terminal *fill* states `FILLED`/`PARTIALLY_FILLED`.
- `deleted` filter: **not applied** to the 891 (includes soft-deleted). With `deleted=false` the ZERODHA breakdown is `REJECTED 99, CANCELLED 16, FAILED 6` (still 0 fills). So the result holds with or without the `deleted` filter.

## Cross-check via executions (actual fill records)
```sql
SELECT o.broker_vendor, count(e.*) FROM oms_executions e
JOIN oms_orders o ON o.id=e.order_id GROUP BY o.broker_vendor;
-- SIM 260, ZERODHA 22
```
The 22 ZERODHA-linked executions are **not real fills**:
- All 22 sit on orders in state **`CANCELLED`** (never `FILLED`).
- Their `broker_order_id` are **synthetic UUIDs** (`3b06ed75-67d0-375d-…`) — Zerodha real order ids are numeric strings; these are internally generated (simulation/synthetic broker path).
- `is_simulation=false` but no order reached a FILLED state and no numeric broker ack exists.
Dates 2026-05-26 → 06-05.

## Contrast — SIM (paper) does fill
`oms_orders` deleted=false: `SIM FILLED 49, CANCELLED 204, REJECTED 90`; executions SIM=260. Paper fills are real within the simulator.

## Is "0 fills" accurate?
**Accurate, with one clarification:** **0 orders ever reached `FILLED`/`PARTIALLY_FILLED` on ZERODHA**, and **0 real (numeric-broker-id) Zerodha fills exist**. The literal "891 attempts" = all ZERODHA `oms_orders` rows (all states, incl. soft-deleted). If "attempt" is restricted to non-deleted it is 121 (99+16+6); either way fills = 0. The 22 execution rows are synthetic/cancelled, not market fills.

## Confidence: **HIGH** — primary table aggregate + executions cross-check + synthetic-id inspection all agree.
