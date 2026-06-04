-- Re-apply V85 cleanup if startup activation re-created NIFTY bindings for currency strategies.

DELETE FROM strategy_runtime_bindings b
USING strategy_definitions sd, strategy_universe_groups sug
WHERE b.strategy_catalog_id = sd.id
  AND b.universe_group_id = sug.id
  AND sd.strategy_key IN ('USDINR_MOMENTUM', 'EURINR_MEAN_REVERSION')
  AND sug.group_key IN ('NIFTY_50', 'NIFTY_100', 'NIFTY_500');
