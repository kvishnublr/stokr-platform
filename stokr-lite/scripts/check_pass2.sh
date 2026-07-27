#!/bin/bash
PGPASSWORD=`$POSTGRES_PASSWORD psql -h localhost -U postgres -d stokr_lite -t -A -F"|" -c "SELECT id, email, role, password_hash FROM users;"

