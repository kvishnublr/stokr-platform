-- DEBUG QUERY: Analyze today's signals and their exit orders
-- Run this on the production database to understand position orphaning

SELECT 'SIGNALS TABLE' as DEBUG_SECTION;
SELECT
  ss.id as signal_id,
  ss.strategy_name,
  ss.symbol,
  ss.side,
  ss.confidence_score,
  ss.outcome_status as outcome,
  ss.final_pnl,
  ss.created_at,
  COUNT(DISTINCT oo.id) as total_oms_orders,
  COUNT(DISTINCT CASE WHEN oo.order_type LIKE '%exit%' OR oo.idempotency_key LIKE '%outcome-exit%' THEN oo.id END) as exit_orders
FROM strategy_signal ss
LEFT JOIN oms_order oo ON ss.id = oo.signal_id
WHERE DATE(ss.created_at) = CURDATE()
  AND ss.symbol IN ('ADANIPORTS', 'JSWSTEEL', 'KOTAKBANK', 'ICICIBANK', 'SBIN', 'HCLTECH')
GROUP BY ss.id, ss.strategy_name, ss.symbol, ss.side, ss.confidence_score, ss.outcome_status, ss.final_pnl, ss.created_at
ORDER BY ss.created_at DESC;

SELECT '\nEXIT ORDERS FOR THESE SIGNALS' as DEBUG_SECTION;
SELECT
  oo.id as order_id,
  oo.idempotency_key,
  oo.state,
  oo.symbol,
  oo.side,
  oo.quantity,
  oo.execution_mode,
  oo.broker_vendor,
  oo.broker_order_id,
  oo.reject_reason,
  oo.created_at
FROM oms_order oo
LEFT JOIN strategy_signal ss ON oo.signal_id = ss.id
WHERE DATE(oo.created_at) = CURDATE()
  AND (
    oo.idempotency_key LIKE '%outcome-exit:%'
    OR oo.symbol IN ('ADANIPORTS', 'JSWSTEEL', 'KOTAKBANK', 'ICICIBANK', 'SBIN', 'HCLTECH')
  )
ORDER BY oo.created_at DESC;

SELECT '\nEXECUTIONS FOR THESE ORDERS' as DEBUG_SECTION;
SELECT
  oe.id as execution_id,
  oo.symbol,
  oo.side,
  oe.quantity_filled,
  oe.price,
  oe.execution_status,
  oe.executed_at,
  oo.state as order_state
FROM oms_execution oe
LEFT JOIN oms_order oo ON oe.order_id = oo.id
WHERE DATE(oe.executed_at) = CURDATE()
  AND oo.symbol IN ('ADANIPORTS', 'JSWSTEEL', 'KOTAKBANK', 'ICICIBANK', 'SBIN', 'HCLTECH')
ORDER BY oe.executed_at DESC;

SELECT '\nFINAL BROKER POSITIONS (from last sync)' as DEBUG_SECTION;
SELECT
  symbol,
  broker_qty,
  internal_qty,
  synced_at
FROM broker_position_truth_snapshot
WHERE symbol IN ('ADANIPORTS', 'JSWSTEEL', 'KOTAKBANK', 'ICICIBANK', 'SBIN', 'HCLTECH')
ORDER BY synced_at DESC
LIMIT 10;
