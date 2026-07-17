SELECT id, underlying, strike, action, spot_price, futures_price, 
       edge_points, edge_after_costs, scan_time,
       ROUND(((spot_price - futures_price) / spot_price * 100)::numeric, 3) as fut_deviation_pct
FROM option_arb_opportunities 
WHERE scan_time >= CURRENT_DATE 
ORDER BY scan_time;
