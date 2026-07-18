SELECT underlying, COUNT(*), MIN(edge_after_costs) as min_edge, MAX(edge_after_costs) as max_edge 
FROM option_arb_opportunities 
WHERE status = 'OPEN' 
GROUP BY underlying 
ORDER BY underlying;
