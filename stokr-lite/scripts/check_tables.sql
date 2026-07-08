-- Check available tables and find Zerodha credentials
SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' ORDER BY table_name;

-- Check if there's a zerodha token somewhere
SELECT column_name, table_name FROM information_schema.columns WHERE column_name LIKE '%token%' OR column_name LIKE '%zerodha%' OR column_name LIKE '%api%';
