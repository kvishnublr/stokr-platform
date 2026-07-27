#!/bin/bash
PGPASSWORD=`$POSTGRES_PASSWORD psql -h localhost -U postgres -d stokr_lite -c "SELECT id, email, role, enabled, password IS NOT NULL as has_password FROM users ORDER BY id;"

