import subprocess
result = subprocess.run(
    ['docker', 'exec', 'stokr-postgres', 'psql', '-U', 'postgres', '-d', 'stokr_lite',
     '-c', "ALTER USER stokr WITH PASSWORD 'root123';"],
    capture_output=True, text=True
)
print("STDOUT:", result.stdout)
print("STDERR:", result.stderr)
