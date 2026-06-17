#!/bin/bash
cat /root/stokr-lite/.env
echo '---START-BACKEND---'
cat /root/stokr-lite/start-backend.sh
echo '---USERS---'
docker exec stokr-postgres psql -U postgres -c "\du"
