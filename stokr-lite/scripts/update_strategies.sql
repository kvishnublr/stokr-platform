INSERT INTO strategies (strategy_type, name, description, enabled, timeframe, asset_class)
VALUES ('THREE_RED_DAYS', '3 Red Days', 'Buy after 3 consecutive down days with volume surge. SL 3%, Target +3%, hold 1-5 days.', true, 'DAILY', 'EQUITY');
SELECT id, strategy_type, name FROM strategies WHERE strategy_type = 'THREE_RED_DAYS';
