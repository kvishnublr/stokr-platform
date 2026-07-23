-- Backfill ce_symbol, pe_symbol, fut_symbol for opps that have null symbols
-- Using manual month abbreviation to avoid TO_CHAR issues

-- NIFTY (uses YY+M+DD format)
UPDATE option_arb_opportunities
SET
  ce_symbol = 'NIFTY' || LPAD(((EXTRACT(YEAR FROM expiry_date)::INT % 100)::VARCHAR), 2, '0')
    || ((EXTRACT(MONTH FROM expiry_date)::INT)::VARCHAR)
    || LPAD(((EXTRACT(DAY FROM expiry_date)::INT)::VARCHAR), 2, '0')
    || (strike::INT::VARCHAR) || 'CE',
  pe_symbol = 'NIFTY' || LPAD(((EXTRACT(YEAR FROM expiry_date)::INT % 100)::VARCHAR), 2, '0')
    || ((EXTRACT(MONTH FROM expiry_date)::INT)::VARCHAR)
    || LPAD(((EXTRACT(DAY FROM expiry_date)::INT)::VARCHAR), 2, '0')
    || (strike::INT::VARCHAR) || 'PE',
  fut_symbol = 'NIFTY' || LPAD(((EXTRACT(YEAR FROM expiry_date)::INT % 100)::VARCHAR), 2, '0')
    || CASE EXTRACT(MONTH FROM expiry_date)::INT
        WHEN 1 THEN 'JAN' WHEN 2 THEN 'FEB' WHEN 3 THEN 'MAR' WHEN 4 THEN 'APR'
        WHEN 5 THEN 'MAY' WHEN 6 THEN 'JUN' WHEN 7 THEN 'JUL' WHEN 8 THEN 'AUG'
        WHEN 9 THEN 'SEP' WHEN 10 THEN 'OCT' WHEN 11 THEN 'NOV' WHEN 12 THEN 'DEC'
      END || 'FUT'
WHERE UPPER(underlying) = 'NIFTY' AND (ce_symbol IS NULL OR ce_symbol = '');

-- BANKNIFTY, FINNIFTY, MIDCPNIFTY (uses YY+MON format)
UPDATE option_arb_opportunities
SET
  ce_symbol = UPPER(underlying) || LPAD(((EXTRACT(YEAR FROM expiry_date)::INT % 100)::VARCHAR), 2, '0')
    || CASE EXTRACT(MONTH FROM expiry_date)::INT
        WHEN 1 THEN 'JAN' WHEN 2 THEN 'FEB' WHEN 3 THEN 'MAR' WHEN 4 THEN 'APR'
        WHEN 5 THEN 'MAY' WHEN 6 THEN 'JUN' WHEN 7 THEN 'JUL' WHEN 8 THEN 'AUG'
        WHEN 9 THEN 'SEP' WHEN 10 THEN 'OCT' WHEN 11 THEN 'NOV' WHEN 12 THEN 'DEC'
      END
    || (strike::INT::VARCHAR) || 'CE',
  pe_symbol = UPPER(underlying) || LPAD(((EXTRACT(YEAR FROM expiry_date)::INT % 100)::VARCHAR), 2, '0')
    || CASE EXTRACT(MONTH FROM expiry_date)::INT
        WHEN 1 THEN 'JAN' WHEN 2 THEN 'FEB' WHEN 3 THEN 'MAR' WHEN 4 THEN 'APR'
        WHEN 5 THEN 'MAY' WHEN 6 THEN 'JUN' WHEN 7 THEN 'JUL' WHEN 8 THEN 'AUG'
        WHEN 9 THEN 'SEP' WHEN 10 THEN 'OCT' WHEN 11 THEN 'NOV' WHEN 12 THEN 'DEC'
      END
    || (strike::INT::VARCHAR) || 'PE',
  fut_symbol = UPPER(underlying) || LPAD(((EXTRACT(YEAR FROM expiry_date)::INT % 100)::VARCHAR), 2, '0')
    || CASE EXTRACT(MONTH FROM expiry_date)::INT
        WHEN 1 THEN 'JAN' WHEN 2 THEN 'FEB' WHEN 3 THEN 'MAR' WHEN 4 THEN 'APR'
        WHEN 5 THEN 'MAY' WHEN 6 THEN 'JUN' WHEN 7 THEN 'JUL' WHEN 8 THEN 'AUG'
        WHEN 9 THEN 'SEP' WHEN 10 THEN 'OCT' WHEN 11 THEN 'NOV' WHEN 12 THEN 'DEC'
      END || 'FUT'
WHERE UPPER(underlying) IN ('BANKNIFTY', 'FINNIFTY', 'MIDCPNIFTY') AND (ce_symbol IS NULL OR ce_symbol = '');
