SELECT id, underlying, strike, spot_price, futures_price, ce_entry_price, pe_entry_price, edge_after_costs, status, scan_time 
FROM option_arb_opportunities 
WHERE status = 'DETECTED' OR status = 'ACTIVE'
ORDER BY scan_time DESC;
