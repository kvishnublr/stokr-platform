ALTER TABLE option_arb_opportunities ADD COLUMN IF NOT EXISTS strategy_type VARCHAR(50) DEFAULT 'NORMAL_PARITY';
UPDATE option_arb_opportunities SET strategy_type = 'NORMAL_PARITY' WHERE strategy_type IS NULL;
SELECT strategy_type, COUNT(*) FROM option_arb_opportunities GROUP BY strategy_type;
