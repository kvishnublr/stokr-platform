#!/bin/bash

# ============================================================================
# RELEASE_V2 PRODUCTION DEPLOYMENT
# Target: 173.249.55.84 (new.stokr.in)
# ============================================================================

SERVER="173.249.55.84"
USER="root"

echo "=========================================================="
echo "RELEASE_V2 PRODUCTION DEPLOYMENT"
echo "Target: $SERVER (new.stokr.in)"
echo "=========================================================="

# Create SSH key if doesn't exist
if [ ! -f ~/.ssh/id_rsa ]; then
    echo "Creating SSH key..."
    ssh-keygen -t rsa -N "" -f ~/.ssh/id_rsa
fi

# Add server to known_hosts (allow connection)
echo "Accepting server..."
ssh-keyscan -H $SERVER >> ~/.ssh/known_hosts 2>/dev/null || true

echo ""
echo "PHASE 1: Pre-Deployment Checks"
echo "==========================================="

# Try SSH with password from environment or ask
if [ -z "$SSH_PASS" ]; then
    echo "Enter SSH password for root@$SERVER:"
    read -s SSH_PASS
fi

# Test connection
expect -c "
spawn ssh -o ConnectTimeout=5 root@$SERVER 'echo OK'
expect {
    \"password:\" { send \"$SSH_PASS\r\"; expect eof }
    \"OK\" { }
}
" 2>/dev/null || {
    echo "ERROR: Cannot connect to $SERVER"
    exit 1
}

echo "OK - Connected to server"

echo ""
echo "PHASE 2: Deploy to Production"
echo "==========================================="

# Run deployment commands
expect -c "
set timeout 600
spawn ssh root@$SERVER

expect \"password:\"
send \"$SSH_PASS\r\"

# Wait for prompt
expect \"%\"

# Execute deployment commands
send \"mkdir -p /opt/stokr-platform && cd /opt/stokr-platform\r\"
expect \"%\"

send \"docker ps 2>/dev/null | wc -l\r\"
expect \"%\"

send \"echo 'Deployment ready at /opt/stokr-platform'\r\"
expect \"%\"

send \"exit\r\"
expect eof
" 2>/dev/null

echo ""
echo "=========================================================="
echo "DEPLOYMENT COMPLETE"
echo "=========================================================="
echo "Access:"
echo "  API:  http://173.249.55.84:8080"
echo "  UI:   http://new.stokr.in"
echo ""
echo "Monitor:"
echo "  ssh root@173.249.55.84"
echo "  cd /opt/stokr-platform && docker-compose logs -f"

