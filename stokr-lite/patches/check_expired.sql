SELECT id, underlying, strike, spot_price, futures_price, ce_entry_price, pe_entry_price, edge_after_costs, status, scan_time, days_to_expiry, expiry_date
FROM option_arb_opportunities 
WHERE status = 'EXPIRED'
ORDER BY scan_time DESC
LIMIT 20;
