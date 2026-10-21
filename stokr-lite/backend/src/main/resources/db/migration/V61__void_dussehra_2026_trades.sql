-- NSE was closed on 2026-10-20 (Dussehra), but the holiday list was empty so the cash
-- engine opened paper positions at stale prices. Void them: kept for audit, hidden from
-- open/history views (which read OPEN / CLOSED / EXITED only) and excluded from P&L.
UPDATE cash_positions
   SET status = 'VOID',
       error_message = 'VOID_MARKET_HOLIDAY_2026-10-20',
       current_pnl = 0,
       exited_at = NULL
 WHERE entered_at >= DATE '2026-10-20' AND entered_at < DATE '2026-10-21'
   AND COALESCE(broker, 'PAPER') = 'PAPER';

UPDATE live_positions
   SET status = 'VOID',
       exit_reason = 'VOID_MARKET_HOLIDAY_2026-10-20',
       current_pnl = 0
 WHERE entered_at >= DATE '2026-10-20' AND entered_at < DATE '2026-10-21'
   AND COALESCE(broker, 'PAPER') = 'PAPER';
