INSERT INTO option_arb_auto_exec_settings (setting_key, setting_value) VALUES ('time_filter_enabled', 'true') ON CONFLICT DO NOTHING;
SELECT setting_key, setting_value FROM option_arb_auto_exec_settings ORDER BY setting_key;
