#!/bin/bash
set -e

echo "=== Fix 1: Enable nginx gzip ==="
sed -i 's/^\t# gzip_vary on;/\tgzip_vary on;/' /etc/nginx/nginx.conf
sed -i 's/^\t# gzip_proxied any;/\tgzip_proxied any;/' /etc/nginx/nginx.conf
sed -i 's/^\t# gzip_comp_level 6;/\tgzip_comp_level 6;/' /etc/nginx/nginx.conf
sed -i 's/^\t# gzip_buffers 16 8k;/\tgzip_buffers 16 8k;/' /etc/nginx/nginx.conf
sed -i 's/^\t# gzip_http_version 1.1;/\tgzip_http_version 1.1;/' /etc/nginx/nginx.conf
sed -i 's/^\t# gzip_types text\/plain text\/css application\/json application\/javascript text\/xml application\/xml application\/xml+rss text\/javascript;/\tgzip_types text\/plain text\/css application\/json application\/javascript text\/xml application\/xml application\/xml+rss text\/javascript;/' /etc/nginx/nginx.conf
echo "Gzip enabled"
cat /etc/nginx/nginx.conf | grep -A 10 "Gzip Settings"

echo ""
echo "=== Fix 2: Add static asset caching to nginx site config ==="
# Add location block for static assets with long cache
cat > /etc/nginx/snippets/static-cache.conf << 'CACHEEOF'
# Cache static assets for 1 year (fingerprinted filenames)
location ~* \.(js|css|png|jpg|jpeg|gif|ico|svg|woff|woff2|ttf|eot)$ {
    expires 365d;
    add_header Cache-Control "public, immutable";
    access_log off;
}
CACHEEOF

echo "Static cache snippet created"

# Check if we need to add include to sites-enabled
grep -q "static-cache" /etc/nginx/sites-enabled/default || {
    # Add before the first location block
    sed -i '/location \/api\//i\\tinclude /etc/nginx/snippets/static-cache.conf;\n' /etc/nginx/sites-enabled/default
    echo "Included static-cache in site config"
}

echo ""
echo "=== Fix 3: Test and reload nginx ==="
nginx -t 2>&1
if [ $? -eq 0 ]; then
    systemctl reload nginx
    echo "Nginx reloaded successfully"
else
    echo "Nginx config test FAILED"
    exit 1
fi

echo ""
echo "=== Verify gzip works ==="
curl -s -H 'Accept-Encoding: gzip' -o /dev/null -w 'Size: %{size_download} bytes (compressed)\n' 'http://localhost:8081/assets/react-vendor-BPN2y53-.js'
echo "Original size: $(wc -c < /tmp/jar_extract/BOOT-INF/classes/static/assets/react-vendor-BPN2y53-.js) bytes"
