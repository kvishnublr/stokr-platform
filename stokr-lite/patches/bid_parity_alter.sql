ALTER TABLE option_arb_executed_trades ADD COLUMN IF NOT EXISTS bid_type VARCHAR(20);
ALTER TABLE option_arb_executed_trades ADD COLUMN IF NOT EXISTS ce_bid_qty_entry BIGINT;
ALTER TABLE option_arb_executed_trades ADD COLUMN IF NOT EXISTS pe_bid_qty_entry BIGINT;
ALTER TABLE option_arb_executed_trades ADD COLUMN IF NOT EXISTS ce_bid_price_entry DOUBLE PRECISION;
ALTER TABLE option_arb_executed_trades ADD COLUMN IF NOT EXISTS pe_bid_price_entry DOUBLE PRECISION;
ALTER TABLE option_arb_executed_trades ADD COLUMN IF NOT EXISTS ce_bid_qty_exit BIGINT;
ALTER TABLE option_arb_executed_trades ADD COLUMN IF NOT EXISTS pe_bid_qty_exit BIGINT;
ALTER TABLE option_arb_executed_trades ADD COLUMN IF NOT EXISTS ce_bid_price_exit DOUBLE PRECISION;
ALTER TABLE option_arb_executed_trades ADD COLUMN IF NOT EXISTS pe_bid_price_exit DOUBLE PRECISION;
