-- Standardize monetary/price columns to 2 decimals across runtime-facing tables.
-- Uses dynamic checks so migration is safe even if some tables/columns are absent in older branches.

DO $$
DECLARE
    r RECORD;
BEGIN
    FOR r IN
        SELECT *
        FROM (VALUES
            ('marketdata_ticks', 'price'),
            ('marketdata_candles', 'open_price'),
            ('marketdata_candles', 'high_price'),
            ('marketdata_candles', 'low_price'),
            ('marketdata_candles', 'close_price'),
            ('strategy_signals', 'signal_price'),
            ('strategy_signals', 'stop_price'),
            ('strategy_signals', 'target_price'),
            ('strategy_signals', 'entry_reference_price'),
            ('strategy_signals', 'entry_price'),
            ('strategy_signals', 'exit_price'),
            ('oms_orders', 'signal_price'),
            ('oms_orders', 'stop_price'),
            ('oms_orders', 'target_price'),
            ('oms_orders', 'entry_reference_price'),
            ('oms_orders', 'expected_price'),
            ('oms_executions', 'actual_fill_price'),
            ('oms_executions', 'reference_price'),
            ('portfolio_positions', 'avg_price'),
            ('portfolio_positions', 'mtm_price'),
            ('trades', 'price'),
            ('trade_fills', 'price')
        ) AS v(tbl, col)
    LOOP
        IF EXISTS (
            SELECT 1
            FROM information_schema.columns c
            WHERE c.table_schema = 'public'
              AND c.table_name = r.tbl
              AND c.column_name = r.col
        ) THEN
            EXECUTE format('UPDATE %I SET %I = ROUND(%I::numeric, 2) WHERE %I IS NOT NULL', r.tbl, r.col, r.col, r.col);
            EXECUTE format('ALTER TABLE %I ALTER COLUMN %I TYPE NUMERIC(24,2)', r.tbl, r.col);
        END IF;
    END LOOP;
END $$;

