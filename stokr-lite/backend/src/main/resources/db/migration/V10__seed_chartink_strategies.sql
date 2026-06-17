-- Update existing strategies to new Chartink strategy names
UPDATE strategies SET name = 'VWAP Triple Confirmation', strategy_type = 'VWAP_TRIPLE' WHERE id = 1;
UPDATE strategies SET name = 'Trade Book Imbalance', strategy_type = 'TRADE_BOOK' WHERE id = 2;
UPDATE strategies SET name = 'ORB-V Breakout', strategy_type = 'ORB_V' WHERE id = 3;

-- Insert new strategies for IDs 4 and 5 if not present
INSERT INTO strategies (id, name, description, strategy_type, params_schema, asset_class, enabled, created_at, updated_at)
VALUES (4, 'Morning Surge Reversal', 'Mean reversion short strategy for morning spikes', 'MORNING_SURGE', '{}', 'EQUITY', true, NOW(), NOW())
ON CONFLICT (id) DO UPDATE SET
    name = EXCLUDED.name,
    description = EXCLUDED.description,
    strategy_type = EXCLUDED.strategy_type;

INSERT INTO strategies (id, name, description, strategy_type, params_schema, asset_class, enabled, created_at, updated_at)
VALUES (5, 'Pre-Open Trade Book', 'Pre-market momentum based on unfilled orders', 'PRE_OPEN', '{}', 'EQUITY', true, NOW(), NOW())
ON CONFLICT (id) DO UPDATE SET
    name = EXCLUDED.name,
    description = EXCLUDED.description,
    strategy_type = EXCLUDED.strategy_type;
