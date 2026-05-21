-- V46: Force strategy_instances symbol assignment from active runtime bindings
-- Purpose: remove legacy dependency on STOKR_STRATEGY_SYMBOLS / NIFTY_FUT defaults.

WITH ranked_binding_symbols AS (
    SELECT
        rb.strategy_catalog_id,
        COALESCE(us.trading_symbol, us.symbol) AS resolved_symbol,
        ROW_NUMBER() OVER (
            PARTITION BY rb.strategy_catalog_id
            ORDER BY ug.group_key ASC, COALESCE(us.trading_symbol, us.symbol) ASC
        ) AS rn
    FROM strategy_runtime_bindings rb
    JOIN strategy_universe_groups ug
      ON ug.id = rb.universe_group_id
    JOIN strategy_universe_symbols us
      ON us.group_id = ug.id
    WHERE rb.runtime_enabled = TRUE
      AND ug.enabled = TRUE
      AND us.enabled = TRUE
), chosen_symbol_per_strategy AS (
    SELECT strategy_catalog_id, resolved_symbol
    FROM ranked_binding_symbols
    WHERE rn = 1
)
UPDATE strategy_instances si
SET symbol = c.resolved_symbol,
    updated_at = NOW(),
    version = COALESCE(si.version, 0) + 1
FROM chosen_symbol_per_strategy c
WHERE si.definition_id = c.strategy_catalog_id
  AND si.deleted = FALSE
  AND si.symbol IS DISTINCT FROM c.resolved_symbol;
