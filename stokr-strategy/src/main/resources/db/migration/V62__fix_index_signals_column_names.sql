-- Align index_signals columns with Hibernate naming (camelCase segments before digits stay attached).

ALTER TABLE index_signals RENAME COLUMN momentum_5m TO momentum5m;
ALTER TABLE index_signals RENAME COLUMN trend_30m TO trend30m;
ALTER TABLE index_signals RENAME COLUMN recent_3min_low TO recent3min_low;
ALTER TABLE index_signals RENAME COLUMN recent_3min_high TO recent3min_high;
