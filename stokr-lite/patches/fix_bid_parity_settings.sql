UPDATE option_arb_auto_exec_settings SET setting_value = '1' WHERE setting_key = 'bid_parity_auto_exit' AND setting_value = '1.0';
UPDATE option_arb_auto_exec_settings SET setting_value = '0' WHERE setting_key = 'bid_parity_auto_enabled' AND setting_value = '0.0';
UPDATE option_arb_auto_exec_settings SET setting_value = '3' WHERE setting_key = 'scanner_maxPositionsPerCycle' AND setting_value = '3.0';
