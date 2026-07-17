SELECT id, underlying, strike, action, spot_price, futures_price, ce_entry_price, pe_entry_price, scan_time, edge_points, edge_after_costs 
FROM option_arb_opportunities 
WHERE scan_time >= CURRENT_DATE AND underlying = 'NIFTY' 
ORDER BY scan_time 
LIMIT 10;
