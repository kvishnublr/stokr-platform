SELECT scan_time::date as day, underlying, COUNT(*) as opps, 
  ROUND(AVG(edge_after_costs)::numeric, 0) as avg_edge,
  ROUND(MIN(edge_after_costs)::numeric, 0) as min_edge,
  ROUND(MAX(edge_after_costs)::numeric, 0) as max_edge
FROM option_arb_opportunities 
WHERE scan_time >= '2026-07-15'
GROUP BY scan_time::date, underlying
ORDER BY day DESC, underlying;
