-- Add linked execution support to OMS orders
-- Tracks execution linkage between LIVE and PAPER orders for synchronized execution

ALTER TABLE oms_orders ADD COLUMN IF NOT EXISTS execution_linkage VARCHAR(32);
ALTER TABLE oms_orders ADD COLUMN IF NOT EXISTS execution_linkage_reason VARCHAR(256);

-- Create index for finding paired orders
CREATE INDEX IF NOT EXISTS idx_oms_orders_paired_order_id ON oms_orders(paired_order_id);

-- Create index for finding linked orders
CREATE INDEX IF NOT EXISTS idx_oms_orders_execution_linkage ON oms_orders(execution_linkage);

COMMENT ON COLUMN oms_orders.execution_linkage IS 'Type of linkage: SYNC_PAPER (LIVE gates PAPER), GATED_BY_LIVE (PAPER waits for LIVE), null for unlinked';
COMMENT ON COLUMN oms_orders.execution_linkage_reason IS 'Human-readable reason for the linkage';
