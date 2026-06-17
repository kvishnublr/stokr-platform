export PGPASSWORD=root123
psql -h localhost -U stokr -d stokr_lite -c "DELETE FROM flyway_schema_history WHERE version = '3';"
