import re

with open('/etc/nginx/conf.d/default.conf', 'r') as f:
    content = f.read()

webhook_block = '''    # Webhook proxy to Spring Boot backend
    location /webhooks/ {
        proxy_pass http://localhost:8070;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

'''

content = content.replace('    location /actuator/ {', webhook_block + '    location /actuator/ {')

with open('/etc/nginx/conf.d/default.conf', 'w') as f:
    f.write(content)

print('OK')
