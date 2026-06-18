#!/bin/bash
docker exec stokr-postgres psql -U postgres -c "ALTER USER stokr WITH PASSWORD 'stokr';"
