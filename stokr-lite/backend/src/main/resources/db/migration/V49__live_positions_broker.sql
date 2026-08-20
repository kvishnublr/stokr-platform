-- "PAPER" or the live broker name a position was actually entered through -- lets the Live
-- Positions view tell real orders apart from paper ones even after the execution broker
-- dropdown is switched. Existing rows have no order IDs recorded for their legs (paper
-- entries never got real broker order IDs), so backfill them as PAPER; anything with a
-- real ce_order_id/pe_order_id was genuinely placed live.
ALTER TABLE live_positions ADD COLUMN IF NOT EXISTS broker VARCHAR(20);

UPDATE live_positions SET broker = 'PAPER'
WHERE broker IS NULL AND ce_order_id IS NULL AND pe_order_id IS NULL AND fut_order_id IS NULL;
