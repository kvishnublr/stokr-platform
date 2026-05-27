-- KNN pattern memory persistence for ADV_CASH strategy
-- Stores resolved trade feature vectors + outcomes for KNN classification
-- Survives across deploys (previously lost on restart)

CREATE TABLE knn_pattern_entries (
    id          UUID            PRIMARY KEY,
    created_at  TIMESTAMPTZ     NOT NULL,
    updated_at  TIMESTAMPTZ     NOT NULL,
    version     BIGINT          NOT NULL DEFAULT 0,
    deleted     BOOLEAN         NOT NULL DEFAULT FALSE,

    strategy_key    VARCHAR(50)     NOT NULL,
    symbol          VARCHAR(50)     NOT NULL,

    -- Normalized 6-feature vector (0..1 range)
    feat_obi        DOUBLE PRECISION NOT NULL,
    feat_slope      DOUBLE PRECISION NOT NULL,
    feat_vol        DOUBLE PRECISION NOT NULL,
    feat_vix        DOUBLE PRECISION NOT NULL,
    feat_time       DOUBLE PRECISION NOT NULL,
    feat_direction  DOUBLE PRECISION NOT NULL,

    outcome         INTEGER         NOT NULL,  -- 1=win, 0=loss
    trade_time      TIMESTAMPTZ     NOT NULL,

    -- Raw (un-normalized) debug values
    raw_obi         DOUBLE PRECISION,
    raw_slope       DOUBLE PRECISION,
    raw_vol         DOUBLE PRECISION,
    raw_vix         DOUBLE PRECISION,
    raw_time_min    INTEGER,
    raw_direction   VARCHAR(10)
);

CREATE INDEX idx_knn_pattern_strategy_key ON knn_pattern_entries (strategy_key);
CREATE INDEX idx_knn_pattern_trade_time   ON knn_pattern_entries (strategy_key, trade_time);
