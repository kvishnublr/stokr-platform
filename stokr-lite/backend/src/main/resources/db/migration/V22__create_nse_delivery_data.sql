CREATE TABLE IF NOT EXISTS nse_delivery_data (
    id          BIGSERIAL PRIMARY KEY,
    trade_date  DATE         NOT NULL,
    symbol      VARCHAR(50)  NOT NULL,
    series      VARCHAR(10),
    open_price  DECIMAL(12,2),
    high_price  DECIMAL(12,2),
    low_price   DECIMAL(12,2),
    close_price DECIMAL(12,2),
    prev_close  DECIMAL(12,2),
    total_qty   BIGINT,
    deliv_qty   BIGINT,
    deliv_pct   DECIMAL(6,2),
    high_52w    DECIMAL(12,2),
    low_52w     DECIMAL(12,2),
    created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_nse_delivery_date_symbol UNIQUE (trade_date, symbol)
);

CREATE INDEX IF NOT EXISTS idx_nse_delivery_date    ON nse_delivery_data(trade_date);
CREATE INDEX IF NOT EXISTS idx_nse_delivery_symbol  ON nse_delivery_data(symbol);
CREATE INDEX IF NOT EXISTS idx_nse_delivery_pct     ON nse_delivery_data(deliv_pct);
