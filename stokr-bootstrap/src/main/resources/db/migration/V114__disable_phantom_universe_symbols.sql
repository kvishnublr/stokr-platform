-- V114: Disable phantom/delisted symbols polluting the equity scan universes.
--
-- Audit 2026-06-12: these symbols are not tradeable NSE equities. They carry only
-- synthetic backfill candles (uniform 2028 rows ending 2026-05-25) or none at all,
-- so every live scan integrity-blocks on them (16-30 of 90 NIFTY_100 symbols were
-- reported FEED_STALE all session) and the A+ scanner opened phantom trades against
-- them (e.g. LTIM @ 95.73, delisted HDFC).
--
--   AUTHORSCHAIN, AZORAAUTOS, BOUNSOUL, CARWALE, CCTECH, CEBA, CHAMP, CHATRATH,
--   CHLCS, CWRDVPN, DEEPINDUST, ARIHANT  -> not NSE tickers (fabricated seed data)
--   HDFC      -> merged into HDFCBANK (July 2023)
--   DCB       -> ticker is DCBBANK
--   BAJAJCORP -> renamed BAJAJCON
--   ALEMBICPHARM -> ticker is APLLTD
--
-- LTIM is a real NIFTY 50 constituent and stays enabled; its missing feed is a
-- websocket pin-resolution issue handled in code (universe_pin_failed logging).

UPDATE strategy_universe_symbols
SET enabled = false
WHERE enabled = true
  AND instrument_type = 'EQ'
  AND symbol IN (
    'AUTHORSCHAIN', 'AZORAAUTOS', 'BOUNSOUL', 'CARWALE', 'CCTECH', 'CEBA',
    'CHAMP', 'CHATRATH', 'CHLCS', 'CWRDVPN', 'DEEPINDUST', 'ARIHANT',
    'HDFC', 'DCB', 'BAJAJCORP', 'ALEMBICPHARM'
  );
