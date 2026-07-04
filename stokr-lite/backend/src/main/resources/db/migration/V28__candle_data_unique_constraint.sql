-- V28: Add unique constraint on candle_data to support ON CONFLICT upsert
-- This is required for both the live data scheduler and historical backfill

ALTER TABLE candle_data
    ADD CONSTRAINT uq_candle_symbol_timeframe_timestamp
    UNIQUE (symbol, timeframe, "timestamp");
