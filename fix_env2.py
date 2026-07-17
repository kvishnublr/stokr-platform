env_path = '/opt/stokr/stokr-platform/stokr-lite/.env'
content = """ZERODHA_API_KEY=zazlrld244cc6jf0
ZERODHA_API_SECRET=iyc7m8166tb6i95gt829q6mzbzvmfq6k
ZERODHA_REDIRECT_URI=https://stokr.in/api/brokers/zerodha/callback
STOKR_UI_BASE_URL=https://stokr.in
SPRING_DATASOURCE_URL=jdbc:postgresql://host.docker.internal:5432/stokr_lite
SPRING_DATASOURCE_USERNAME=postgres
SPRING_DATASOURCE_PASSWORD=stokr2026
"""
with open(env_path, 'w') as f:
    f.write(content)
print('Fixed .env: DB_URL uses host.docker.internal')
