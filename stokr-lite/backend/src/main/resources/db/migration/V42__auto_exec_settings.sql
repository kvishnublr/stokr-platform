CREATE TABLE IF NOT EXISTS auto_exec_settings (
    id BIGSERIAL PRIMARY KEY,
    setting_key VARCHAR(100) UNIQUE NOT NULL,
    setting_value TEXT,
    updated_at TIMESTAMP DEFAULT now()
);

INSERT INTO auto_exec_settings (setting_key, setting_value) VALUES
    ('enabled', 'false'),
    ('broker', 'NAVIA'),
    ('niftyEnabled', 'false'),
    ('niftyMinEdge', '2000.0'),
    ('niftyLots', '1'),
    ('bankniftyEnabled', 'false'),
    ('bankniftyMinEdge', '2000.0'),
    ('bankniftyLots', '1'),
    ('finniftyEnabled', 'false'),
    ('finniftyMinEdge', '2000.0'),
    ('finniftyLots', '1'),
    ('midcpniftyEnabled', 'false'),
    ('midcpniftyMinEdge', '2000.0'),
    ('midcpniftyLots', '1'),
    ('maxOpenPositions', '5'),
    ('maxDailyLoss', '5000.0'),
    ('strategyFilter', 'ALL')
ON CONFLICT (setting_key) DO NOTHING;

CREATE INDEX IF NOT EXISTS idx_auto_exec_settings_key ON auto_exec_settings(setting_key);
