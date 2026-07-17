-- List all tables
SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' ORDER BY table_name;

-- Check deployments columns
SELECT column_name FROM information_schema.columns WHERE table_name = 'deployments' ORDER BY ordinal_position;
