UPDATE option_arb_auto_exec_settings SET setting_value = '3' WHERE setting_key = 'max_positions_per_underlying';
UPDATE option_arb_auto_exec_settings SET setting_value = '12' WHERE setting_key = 'max_total_positions';
UPDATE option_arb_auto_exec_settings SET setting_value = 'true' WHERE setting_key = 'time_filter_enabled';
SELECT setting_key, setting_value FROM option_arb_auto_exec_settings WHERE setting_key IN ('max_positions_per_underlying', 'max_total_positions', 'time_filter_enabled', 'auto_execute_enabled');
