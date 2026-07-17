-- FINAL VERIFICATION
SELECT '=== DB STRATEGIES ===' as section;
SELECT id, name, strategy_type, timeframe, enabled FROM strategies ORDER BY id;

SELECT '=== DB DEPLOYMENTS ===' as section;
SELECT id, strategy_id, capital, mode, status FROM deployments ORDER BY id;

SELECT '=== JAVA FILES ===' as section;
