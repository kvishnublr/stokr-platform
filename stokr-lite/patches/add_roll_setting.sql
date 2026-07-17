INSERT INTO option_arb_auto_exec_settings (setting_key, setting_value)
VALUES ('roll_threshold_pct', '5.0')
ON CONFLICT (setting_key) DO NOTHING;
