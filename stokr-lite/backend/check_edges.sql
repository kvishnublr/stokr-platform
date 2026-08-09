SELECT AVG(edge_after_costs) as avg_edge, MIN(edge_after_costs) as min_edge, MAX(edge_after_costs) as max_edge, COUNT(*) as total_signals,
  SUM(CASE WHEN edge_after_costs >= 1000 THEN 1 ELSE 0 END) as signals_above_1k,
  SUM(CASE WHEN edge_after_costs >= 500 THEN 1 ELSE 0 END) as signals_above_500,
  SUM(CASE WHEN edge_after_costs >= 300 THEN 1 ELSE 0 END) as signals_above_300
FROM option_arb_opportunities 
WHERE scan_time >= '2026-08-03' AND edge_after_costs > 0;
