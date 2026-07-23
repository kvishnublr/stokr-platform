-- DELETE the 14 fabricated manually-inserted Jul 20 opportunities (wrong prices)
DELETE FROM option_arb_opportunities WHERE status = 'DETECTED';

-- DELETE entries with broken futures_price = 0 (scanner couldn't fetch futures)
DELETE FROM option_arb_opportunities WHERE futures_price = 0 OR futures_price IS NULL;

-- Mark stale ACTIVE entry as EXPIRED (it's from Jul 15)
UPDATE option_arb_opportunities SET status = 'EXPIRED' WHERE status = 'ACTIVE';

-- Verify clean data
SELECT status, COUNT(*), MIN(scan_time) as earliest, MAX(scan_time) as latest FROM option_arb_opportunities GROUP BY status;
