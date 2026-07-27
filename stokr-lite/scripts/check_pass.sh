#!/bin/bash
PGPASSWORD=`$POSTGRES_PASSWORD psql -h localhost -U postgres -d stokr_lite -c "SELECT id, email, role, password_hash FROM users;"

