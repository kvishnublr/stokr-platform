#!/bin/bash
set -e

echo "=== Fix: Remove broken static-cache snippet ==="
# Remove the broken snippet include
sed -i '/include \/etc\/nginx\/snippets\/static-cache.conf;/d' /etc/nginx/sites-enabled/default

echo ""
echo "=== Add proper static asset caching + proxy ==="
# Read the current site config and find the right place to add
cat /etc/nginx/sites-enabled/default | head -50
