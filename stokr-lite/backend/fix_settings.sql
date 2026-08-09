UPDATE option_arb_auto_exec_settings SET setting_value = 'true' WHERE setting_key = 'finniftyEnabled';
UPDATE option_arb_auto_exec_settings SET setting_value = 'true' WHERE setting_key = 'midcpniftyEnabled';
UPDATE option_arb_auto_exec_settings SET setting_value = '300' WHERE setting_key = 'finniftyMinEdge';
UPDATE option_arb_auto_exec_settings SET setting_value = '300' WHERE setting_key = 'midcpniftyMinEdge';
UPDATE option_arb_auto_exec_settings SET setting_value = 'ALL' WHERE setting_key = 'strategyFilter';
