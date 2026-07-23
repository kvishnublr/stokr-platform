SELECT COUNT(*) as broken_futures FROM option_arb_opportunities WHERE futures_price = 0 OR futures_price IS NULL;
SELECT COUNT(*) as detected FROM option_arb_opportunities WHERE status = 'DETECTED';
SELECT COUNT(*) as active FROM option_arb_opportunities WHERE status = 'ACTIVE';
