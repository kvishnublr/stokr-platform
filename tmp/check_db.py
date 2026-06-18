import subprocess
import os

os.environ['PGPASSWORD'] = 'root123'
result = subprocess.run(
    ['psql', '-h', 'localhost', '-U', 'stokr', '-d', 'postgres',
     '-c', "SELECT datname FROM pg_database WHERE datname LIKE '%stokr%'"],
    capture_output=True, text=True
)
print(result.stdout)
print(result.stderr)
