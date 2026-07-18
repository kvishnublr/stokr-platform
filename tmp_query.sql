SELECT underlying, strike, opportunity_type FROM option_arb_opportunities WHERE scan_time::date = CURRENT_DATE AND opportunity_type = 'PARITY_BREAK' ORDER BY strike LIMIT 5;
