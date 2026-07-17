import paramiko,time
s=paramiko.SSHClient();s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84',username='root',password='19119e3a6793dde1',timeout=30)
def c(cmd):
    i,o,e = s.exec_command(cmd)
    return o.read().decode(errors='replace').strip()

# Kill everything
c("docker rm -f stokr-lite-backend stokr-lite-frontend 2>/dev/null")
c("fuser -k 8080/tcp 2>/dev/null")

# Pull latest code
print("Pulling code...")
print(c("cd /root/stokr-platform && git stash && git pull origin Release_v8 2>&1 | tail -3"))

# Fix filenames
c("cd /root/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/strategy && ls Institutional* 2>/dev/null || (rm -f institutionalfootprintstrategy.java 2>/dev/null; echo 'no Institutional file needs fix')")

# Seed V35 directly
print("Seeding DB...")
print(c("su - postgres -c \"psql -d stokr_lite -c \\\"INSERT INTO strategies (name, description, strategy_type, asset_class, params_schema, enabled, created_at, updated_at) VALUES ('Institutional Footprint', 'VSA Smart Money Engine', 'INSTITUTIONAL_FOOTPRINT', 'EQUITY', '{\\\"max_positions\\\":2}', true, now(), now()) ON CONFLICT (name) DO UPDATE SET enabled=true\\\"\" 2>&1"))

# Build JAR directly on host
print("\nBuilding JAR directly (this takes 2-3 min)...")
out = c("cd /root/stokr-platform/stokr-lite/backend && mvn clean package -DskipTests -q 2>&1 | tail -5")
print(out)

# Check if JAR exists
jar_out = c("ls -la /root/stokr-platform/stokr-lite/backend/target/stokr-lite-1.0.0-SNAPSHOT.jar 2>/dev/null")

if 'stokr-lite-' in jar_out:
    print("JAR built successfully")
    # Copy to app.jar location
    c("cp /root/stokr-platform/stokr-lite/backend/target/stokr-lite-1.0.0-SNAPSHOT.jar /root/stokr-platform/stokr-lite/backend/app.jar")
    
    # Start with docker compose (will use the built JAR via build context)
    print("Starting containers...")
    out2 = c("cd /root/stokr-platform/stokr-lite && docker compose up -d --build backend 2>&1 | tail -8")
    print(out2[-400:])
    
    # Wait
    time.sleep(35)
    print("Health:", c("curl -s -m3 http://localhost:8080/actuator/health 2>/dev/null || echo 'down'"))
    
    # If still restarting, run directly
    ps = c("docker ps --format '{{.Names}} {{.Status}}' | grep backend")
    print("Container:", ps)
    
    if 'Restarting' in ps or not ps:
        print("Running JAR directly...")
        c("fuser -k 8080/tcp 2>/dev/null; sleep 2")
        c("nohup java -jar /root/stokr-platform/stokr-lite/backend/app.jar --spring.profiles.active=default > /var/log/stokr.log 2>&1 &")
        time.sleep(30)
        print("Health (direct):", c("curl -s -m3 http://localhost:8080/actuator/health 2>/dev/null || echo 'down'"))
else:
    print("JAR build FAILED!")
    print(c("cd /root/stokr-platform/stokr-lite/backend && mvn compile -DskipTests 2>&1 | grep 'ERROR' | head -10"))
s.close()
