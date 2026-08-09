UPDATE option_arb_auto_exec_settings SET setting_value = '1' WHERE setting_key IN ('niftyLots', 'bankniftyLots', 'finniftyLots', 'midcpniftyLots');
SELECT setting_key, setting_value FROM option_arb_auto_exec_settings WHERE setting_key LIKE '%Lots%';
