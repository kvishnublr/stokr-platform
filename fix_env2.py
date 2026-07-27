import os

env_path = '/opt/stokr/stokr-platform/stokr-lite/.env'
content = f"""ZERODHA_API_KEY={os.environ.get('ZERODHA_API_KEY','')}
ZERODHA_API_SECRET={os.environ.get('ZERODHA_API_SECRET','')}
ZERODHA_REDIRECT_URI=https://stokr.in/api/brokers/zerodha/callback
STOKR_UI_BASE_URL=https://stokr.in
SPRING_DATASOURCE_URL=jdbc:postgresql://host.docker.internal:5432/stokr_lite
SPRING_DATASOURCE_USERNAME=postgres
SPRING_DATASOURCE_PASSWORD={os.environ.get('POSTGRES_PASSWORD','')}
"""
with open(env_path, 'w') as f:
    f.write(content)
print('Fixed .env: DB_URL uses host.docker.internal')
