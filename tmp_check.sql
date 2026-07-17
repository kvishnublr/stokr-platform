INSERT INTO option_arb_auto_exec_settings (setting_key, setting_value, description) VALUES
    ('target_underlying', 'ALL', 'Which underlyings to auto-execute: ALL, NIFTY, BANKNIFTY, MIDCPNIFTY, FINNIFTY, or comma-separated like NIFTY,BANKNIFTY')
ON CONFLICT (setting_key) DO NOTHING;
