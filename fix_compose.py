path = '/opt/stokr/stokr-platform/stokr-lite/docker-compose.yml'
with open(path, 'r') as f:
    content = f.read()

# Add extra_hosts to backend service
old = '''    ports:
      - "8081:8080"
    restart: unless-stopped
    networks:
      - stokr-net'''

new = '''    extra_hosts:
      - "host.docker.internal:host-gateway"
    ports:
      - "8081:8080"
    restart: unless-stopped
    networks:
      - stokr-net'''

if 'extra_hosts' not in content:
    content = content.replace(old, new)
    with open(path, 'w') as f:
        f.write(content)
    print('Added extra_hosts to docker-compose.yml')
else:
    print('extra_hosts already present')
