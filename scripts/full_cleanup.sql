-- FULL CLEANUP: Remove all losing/dead strategies
-- KEEP: 4(MSR), 15(OB), 21(EMA50D), 23(TRD), 31(RSIO)
-- REMOVE: 16(MVR), 18(IF), 20(IF_V2), 24(NCS), 25(IM)

-- Step 1: Check what we're deleting
SELECT 'BEFORE CLEANUP:' as status;
SELECT 'strategies' as tbl, count(*) as cnt FROM strategies WHERE id IN (16, 18, 20, 24, 25)
UNION ALL SELECT 'deployments', count(*) FROM deployments WHERE strategy_id IN (16, 18, 20, 24, 25)
UNION ALL SELECT 'universe_maps', count(*) FROM strategy_universe_mappings WHERE strategy_id IN (16, 18, 20, 24, 25)
UNION ALL SELECT 'signals', count(*) FROM strategy_signals WHERE strategy_id IN (16, 18, 20, 24, 25)
UNION ALL SELECT 'orders (via signals)', count(*) FROM orders o JOIN strategy_signals s ON o.signal_id = s.id WHERE s.strategy_id IN (16, 18, 20, 24, 25)
UNION ALL SELECT 'positions', count(*) FROM positions WHERE deployment_id IN (SELECT id FROM deployments WHERE strategy_id IN (16, 18, 20, 24, 25));

-- Step 2: Delete in order (respect FKs)
DELETE FROM orders WHERE signal_id IN (SELECT id FROM strategy_signals WHERE strategy_id IN (16, 18, 20, 24, 25));
DELETE FROM positions WHERE deployment_id IN (SELECT id FROM deployments WHERE strategy_id IN (16, 18, 20, 24, 25));
DELETE FROM strategy_signals WHERE strategy_id IN (16, 18, 20, 24, 25);
DELETE FROM strategy_universe_mappings WHERE strategy_id IN (16, 18, 20, 24, 25);
DELETE FROM deployments WHERE strategy_id IN (16, 18, 20, 24, 25);
DELETE FROM strategies WHERE id IN (16, 18, 20, 24, 25);

-- Step 3: Also clean up MSR deployment #6 (STOPPED, paper, no longer needed)
DELETE FROM deployments WHERE id = 6;

-- Step 4: Also stop MSR from scanning (it's marginal for ₹1L)
UPDATE strategies SET enabled = false WHERE id = 4;

-- Step 5: Verify
SELECT 'AFTER CLEANUP:' as status;
SELECT id, name, strategy_type, timeframe, enabled FROM strategies ORDER BY id;
SELECT '---';
SELECT id, strategy_id, capital, mode, status FROM deployments ORDER BY id;
