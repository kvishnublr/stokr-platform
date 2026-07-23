SELECT id, underlying, strike, spot_price, futures_price, status, scan_time FROM option_arb_opportunities WHERE futures_price = 0 OR futures_price IS NULL;
