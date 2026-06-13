-- V116: Terminal exit-dispatch disposition on strategy_signals.
--
-- The outcome-exit backfill (SignalOutcomeExitService.scheduledBackfill) keyed its
-- "already handled" check solely on the existence of an outcome-exit:* OMS order.
-- Signals whose entry legs were all REJECTED/CANCELLED never get an exit leg, so
-- they re-entered the scan every 5 minutes forever (124 signals looping in prod on
-- 2026-06-13) and crowded out the 20-per-cycle dispatch budget.
--
-- outcome_exit_disposition records the terminal decision once:
--   EXIT_PLACED    - at least one exit leg was created for this signal
--   NO_EXIT_NEEDED - no fillable entry legs existed; nothing to unwind
-- Null means not yet evaluated; only null rows are scanned.

ALTER TABLE strategy_signals
    ADD COLUMN IF NOT EXISTS outcome_exit_disposition VARCHAR(32);

-- Settle history in one pass instead of letting the scheduler chew through it:
-- terminal signals that already have an exit leg are EXIT_PLACED ...
UPDATE strategy_signals s
SET outcome_exit_disposition = 'EXIT_PLACED'
WHERE s.deleted = false
  AND s.outcome_exit_disposition IS NULL
  AND s.outcome_status IN ('TARGET_HIT','STOPLOSS_HIT','SL_HIT','BREAKEVEN_EXIT',
                           'PRESSURE_EXIT','LIQUIDITY_PROTECTION','FEED_PROTECTION','TIME_EXIT')
  AND EXISTS (
      SELECT 1 FROM oms_orders o
      WHERE o.deleted = false
        AND o.idempotency_key LIKE 'outcome-exit:' || s.id || ':%'
  );

-- ... and terminal signals older than 30 minutes with no FILLED/PARTIALLY_FILLED/ACCEPTED
-- entry leg (directly linked or paired) need no exit.
UPDATE strategy_signals s
SET outcome_exit_disposition = 'NO_EXIT_NEEDED'
WHERE s.deleted = false
  AND s.outcome_exit_disposition IS NULL
  AND s.outcome_status IN ('TARGET_HIT','STOPLOSS_HIT','SL_HIT','BREAKEVEN_EXIT',
                           'PRESSURE_EXIT','LIQUIDITY_PROTECTION','FEED_PROTECTION','TIME_EXIT')
  AND s.outcome_time < now() - interval '30 minutes'
  AND NOT EXISTS (
      SELECT 1 FROM oms_orders o
      LEFT JOIN oms_orders paired ON paired.id = o.paired_order_id AND paired.deleted = false
      WHERE o.deleted = false
        AND o.signal_id = s.id
        AND (o.state IN ('FILLED','PARTIALLY_FILLED','ACCEPTED')
             OR paired.state IN ('FILLED','PARTIALLY_FILLED','ACCEPTED'))
  );

CREATE INDEX IF NOT EXISTS ix_strategy_signals_outcome_exit_pending
    ON strategy_signals (outcome_time)
    WHERE deleted = false AND outcome_exit_disposition IS NULL;
