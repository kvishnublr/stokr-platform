DELETE FROM option_arb_opportunities WHERE scan_time >= CURRENT_DATE;
SELECT 'Deleted all today records' as status;
