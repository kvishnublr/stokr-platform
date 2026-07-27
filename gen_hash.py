import paramiko
s=paramiko.SSHClient();s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84',username='root',password='`$SSH_PASSWORD',timeout=30)
def c(cmd):
    i,o,e = s.exec_command(cmd)
    return o.read().decode('utf-8',errors='replace').strip()

# Generate BCrypt hash using Java in the Docker container
print("=== Generate BCrypt hash ===")
hash_val = c("docker exec stokr-lite-backend sh -c \"java -cp /app/app.jar -Dloader.main=org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder org.springframework.boot.loader.launch.PropertiesLauncher 2>/dev/null\" 2>/dev/null || echo 'trying alternative'")

# Alternative: use the app's own BCrypt via a simple main
hash_script = """
docker exec stokr-lite-backend java -cp '/app/app.jar:/app/BOOT-INF/lib/*' -e '
try {
    var enc = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
    System.out.println(enc.encode("Admin@123456"));
} catch(Exception e) { e.printStackTrace(); }
' 2>&1 || echo "JAVA_METHOD_FAILED"
"""
print(f"\nTrying BCrypt hash generation: {c(hash_script)[:200]}")

# Simpler: use Python bcrypt on the server
print("\n=== Server Python bcrypt ===")
print(c("pip3 install bcrypt -q 2>/dev/null && python3 -c \"import bcrypt; print(bcrypt.hashpw(b'Admin@123456', bcrypt.gensalt()).decode())\" 2>&1"))

s.close()

