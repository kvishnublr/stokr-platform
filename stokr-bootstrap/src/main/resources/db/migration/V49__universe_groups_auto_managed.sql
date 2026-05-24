-- Universe Groups Auto-Managed Setup
-- Automatically creates all NSE indices as auto-managed universe groups
-- These will be synced on startup with complete symbol lists

-- NIFTY 50
INSERT INTO strategy_universe_groups (id, created_at, updated_at, version, deleted, group_key, display_name, description, universe_type, exchange, asset_class, segment, instrument_type, auto_managed, enabled)
SELECT '11111111-1111-1111-1111-111111110050', NOW(), NOW(), 0, FALSE, 'NIFTY_50', 'NIFTY 50', 'Top 50 NSE stocks', 'NIFTY', 'NSE', 'EQUITY', 'NSE', 'EQ', TRUE, TRUE
WHERE NOT EXISTS (SELECT 1 FROM strategy_universe_groups WHERE group_key = 'NIFTY_50');

-- NIFTY 100
INSERT INTO strategy_universe_groups (id, created_at, updated_at, version, deleted, group_key, display_name, description, universe_type, exchange, asset_class, segment, instrument_type, auto_managed, enabled)
SELECT '11111111-1111-1111-1111-111111110100', NOW(), NOW(), 0, FALSE, 'NIFTY_100', 'NIFTY 100', 'Top 100 NSE stocks', 'NIFTY', 'NSE', 'EQUITY', 'NSE', 'EQ', TRUE, TRUE
WHERE NOT EXISTS (SELECT 1 FROM strategy_universe_groups WHERE group_key = 'NIFTY_100');

-- NIFTY 200
INSERT INTO strategy_universe_groups (id, created_at, updated_at, version, deleted, group_key, display_name, description, universe_type, exchange, asset_class, segment, instrument_type, auto_managed, enabled)
SELECT '11111111-1111-1111-1111-111111110200', NOW(), NOW(), 0, FALSE, 'NIFTY_200', 'NIFTY 200', 'Top 200 NSE stocks', 'NIFTY', 'NSE', 'EQUITY', 'NSE', 'EQ', TRUE, TRUE
WHERE NOT EXISTS (SELECT 1 FROM strategy_universe_groups WHERE group_key = 'NIFTY_200');

-- NIFTY 500
INSERT INTO strategy_universe_groups (id, created_at, updated_at, version, deleted, group_key, display_name, description, universe_type, exchange, asset_class, segment, instrument_type, auto_managed, enabled)
SELECT '11111111-1111-1111-1111-111111110500', NOW(), NOW(), 0, FALSE, 'NIFTY_500', 'NIFTY 500', 'Top 500 NSE stocks', 'NIFTY', 'NSE', 'EQUITY', 'NSE', 'EQ', TRUE, TRUE
WHERE NOT EXISTS (SELECT 1 FROM strategy_universe_groups WHERE group_key = 'NIFTY_500');

-- NIFTY NEXT 50
INSERT INTO strategy_universe_groups (id, created_at, updated_at, version, deleted, group_key, display_name, description, universe_type, exchange, asset_class, segment, instrument_type, auto_managed, enabled)
SELECT '11111111-1111-1111-1111-111111110551', NOW(), NOW(), 0, FALSE, 'NIFTY_NEXT_50', 'NIFTY Next 50', 'Next 50 NSE stocks (51-100)', 'NIFTY', 'NSE', 'EQUITY', 'NSE', 'EQ', TRUE, TRUE
WHERE NOT EXISTS (SELECT 1 FROM strategy_universe_groups WHERE group_key = 'NIFTY_NEXT_50');

-- BANK NIFTY
INSERT INTO strategy_universe_groups (id, created_at, updated_at, version, deleted, group_key, display_name, description, universe_type, exchange, asset_class, segment, instrument_type, auto_managed, enabled)
SELECT '11111111-1111-1111-1111-111111110001', NOW(), NOW(), 0, FALSE, 'BANKNIFTY', 'BANK NIFTY', 'Banking sector stocks', 'BANKNIFTY', 'NSE', 'EQUITY', 'NSE', 'EQ', TRUE, TRUE
WHERE NOT EXISTS (SELECT 1 FROM strategy_universe_groups WHERE group_key = 'BANKNIFTY');

-- BANK NIFTY FUTURES
INSERT INTO strategy_universe_groups (id, created_at, updated_at, version, deleted, group_key, display_name, description, universe_type, exchange, asset_class, segment, instrument_type, auto_managed, enabled)
SELECT '11111111-1111-1111-1111-111111110002', NOW(), NOW(), 0, FALSE, 'BANKNIFTY_FUTURES', 'BANK NIFTY Futures', 'Banking sector stocks (Futures)', 'BANKNIFTY', 'NSE', 'EQUITY', 'NFO', 'FUT', TRUE, TRUE
WHERE NOT EXISTS (SELECT 1 FROM strategy_universe_groups WHERE group_key = 'BANKNIFTY_FUTURES');

-- FIN NIFTY
INSERT INTO strategy_universe_groups (id, created_at, updated_at, version, deleted, group_key, display_name, description, universe_type, exchange, asset_class, segment, instrument_type, auto_managed, enabled)
SELECT '11111111-1111-1111-1111-111111110003', NOW(), NOW(), 0, FALSE, 'FINNIFTY', 'FIN NIFTY', 'Financial sector stocks', 'FINNIFTY', 'NSE', 'EQUITY', 'NSE', 'EQ', TRUE, TRUE
WHERE NOT EXISTS (SELECT 1 FROM strategy_universe_groups WHERE group_key = 'FINNIFTY');

-- FIN NIFTY FUTURES
INSERT INTO strategy_universe_groups (id, created_at, updated_at, version, deleted, group_key, display_name, description, universe_type, exchange, asset_class, segment, instrument_type, auto_managed, enabled)
SELECT '11111111-1111-1111-1111-111111110004', NOW(), NOW(), 0, FALSE, 'FINNIFTY_FUTURES', 'FIN NIFTY Futures', 'Financial sector stocks (Futures)', 'FINNIFTY', 'NSE', 'EQUITY', 'NFO', 'FUT', TRUE, TRUE
WHERE NOT EXISTS (SELECT 1 FROM strategy_universe_groups WHERE group_key = 'FINNIFTY_FUTURES');

-- AUTO NIFTY
INSERT INTO strategy_universe_groups (id, created_at, updated_at, version, deleted, group_key, display_name, description, universe_type, exchange, asset_class, segment, instrument_type, auto_managed, enabled)
SELECT '11111111-1111-1111-1111-111111110005', NOW(), NOW(), 0, FALSE, 'AUTO_NIFTY', 'AUTO NIFTY', 'Automobile sector stocks', 'AUTO', 'NSE', 'EQUITY', 'NSE', 'EQ', TRUE, TRUE
WHERE NOT EXISTS (SELECT 1 FROM strategy_universe_groups WHERE group_key = 'AUTO_NIFTY');

-- IT NIFTY
INSERT INTO strategy_universe_groups (id, created_at, updated_at, version, deleted, group_key, display_name, description, universe_type, exchange, asset_class, segment, instrument_type, auto_managed, enabled)
SELECT '11111111-1111-1111-1111-111111110006', NOW(), NOW(), 0, FALSE, 'IT_NIFTY', 'IT NIFTY', 'IT sector stocks', 'IT', 'NSE', 'EQUITY', 'NSE', 'EQ', TRUE, TRUE
WHERE NOT EXISTS (SELECT 1 FROM strategy_universe_groups WHERE group_key = 'IT_NIFTY');

-- PHARMA NIFTY
INSERT INTO strategy_universe_groups (id, created_at, updated_at, version, deleted, group_key, display_name, description, universe_type, exchange, asset_class, segment, instrument_type, auto_managed, enabled)
SELECT '11111111-1111-1111-1111-111111110007', NOW(), NOW(), 0, FALSE, 'PHARMA_NIFTY', 'PHARMA NIFTY', 'Pharmaceutical sector stocks', 'PHARMA', 'NSE', 'EQUITY', 'NSE', 'EQ', TRUE, TRUE
WHERE NOT EXISTS (SELECT 1 FROM strategy_universe_groups WHERE group_key = 'PHARMA_NIFTY');

-- PSU BANK
INSERT INTO strategy_universe_groups (id, created_at, updated_at, version, deleted, group_key, display_name, description, universe_type, exchange, asset_class, segment, instrument_type, auto_managed, enabled)
SELECT '11111111-1111-1111-1111-111111110008', NOW(), NOW(), 0, FALSE, 'PSU_BANK', 'PSU BANK', 'Public sector bank stocks', 'PSUBANK', 'NSE', 'EQUITY', 'NSE', 'EQ', TRUE, TRUE
WHERE NOT EXISTS (SELECT 1 FROM strategy_universe_groups WHERE group_key = 'PSU_BANK');

-- PRIVATE BANK
INSERT INTO strategy_universe_groups (id, created_at, updated_at, version, deleted, group_key, display_name, description, universe_type, exchange, asset_class, segment, instrument_type, auto_managed, enabled)
SELECT '11111111-1111-1111-1111-111111110009', NOW(), NOW(), 0, FALSE, 'PRIVATE_BANK', 'PRIVATE BANK', 'Private sector bank stocks', 'PRIVATEBANK', 'NSE', 'EQUITY', 'NSE', 'EQ', TRUE, TRUE
WHERE NOT EXISTS (SELECT 1 FROM strategy_universe_groups WHERE group_key = 'PRIVATE_BANK');

-- CONSUMPTION NIFTY
INSERT INTO strategy_universe_groups (id, created_at, updated_at, version, deleted, group_key, display_name, description, universe_type, exchange, asset_class, segment, instrument_type, auto_managed, enabled)
SELECT '11111111-1111-1111-1111-111111110010', NOW(), NOW(), 0, FALSE, 'CONSUMPTION_NIFTY', 'CONSUMPTION NIFTY', 'Consumer stocks', 'CONSUMPTION', 'NSE', 'EQUITY', 'NSE', 'EQ', TRUE, TRUE
WHERE NOT EXISTS (SELECT 1 FROM strategy_universe_groups WHERE group_key = 'CONSUMPTION_NIFTY');

-- INFRA NIFTY
INSERT INTO strategy_universe_groups (id, created_at, updated_at, version, deleted, group_key, display_name, description, universe_type, exchange, asset_class, segment, instrument_type, auto_managed, enabled)
SELECT '11111111-1111-1111-1111-111111110011', NOW(), NOW(), 0, FALSE, 'INFRA_NIFTY', 'INFRA NIFTY', 'Infrastructure stocks', 'INFRA', 'NSE', 'EQUITY', 'NSE', 'EQ', TRUE, TRUE
WHERE NOT EXISTS (SELECT 1 FROM strategy_universe_groups WHERE group_key = 'INFRA_NIFTY');
