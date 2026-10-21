-- V63: Account-level and strategy-level daily loss & profit target limits
INSERT INTO option_arb_auto_exec_settings (setting_key, setting_value) VALUES
    ('globalMaxDailyLoss', '5000.0'),
    ('globalMaxDailyProfit', '10000.0')
ON CONFLICT (setting_key) DO NOTHING;
