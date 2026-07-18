UPDATE option_arb_auto_exec_settings SET setting_value = 'NIFTY,BANKNIFTY' WHERE setting_key = 'target_underlying';
UPDATE option_arb_auto_exec_settings SET setting_value = '3' WHERE setting_key = 'max_total_positions';
UPDATE option_arb_auto_exec_settings SET setting_value = '3' WHERE setting_key = 'max_positions_per_underlying';
SELECT setting_key, setting_value FROM option_arb_auto_exec_settings ORDER BY setting_key;
