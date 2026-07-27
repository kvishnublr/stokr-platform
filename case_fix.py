import paramiko,time
s=paramiko.SSHClient();s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84',username='root',password='`$SSH_PASSWORD',timeout=30)
def c(cmd):
    i,o,e = s.exec_command(cmd)
    return o.read().decode(errors='replace').strip()

base = "/root/stokr-platform/stokr-lite/backend/src/main/java/com/stokr"

# Fix ALL lowercase files on server
fixes = {
    "strategy/momentumsurgestrategy.java": "strategy/_tmp_momentum.java",
    "strategy/institutionalfootprintstrategy.java": "strategy/_tmp_institutional.java",
}

for old_rel, tmp in fixes.items():
    old = f"{base}/{old_rel}"
    tmp_f = f"{base}/{tmp}"
    r = c(f"[ -f {old} ] && mv {old} {tmp_f} && echo 'moved {old_rel}' || echo 'not found {old_rel}'")
    print(r)

# Now move back with correct case
c(f"[ -f {base}/strategy/_tmp_momentum.java ] && mv {base}/strategy/_tmp_momentum.java {base}/strategy/MomentumSurgeStrategy.java && echo 'Fixed MomentumSurgeStrategy' || echo 'no momentum temp'")
c(f"[ -f {base}/strategy/_tmp_institutional.java ] && mv {base}/strategy/_tmp_institutional.java {base}/strategy/InstitutionalFootprintStrategy.java && echo 'Fixed InstitutionalFootprintStrategy' || echo 'no institutional temp'")

# Verify
print("\nStrategy files:")
print(c(f"ls {base}/strategy/*Surge* {base}/strategy/*Institutional* 2>/dev/null"))

# Build
print("\nBuilding...")
out = c("cd /root/stokr-platform/stokr-lite/backend && mvn clean package -DskipTests -q 2>&1 | tail -5")
print(out)

# Check
jar = c("ls -la /root/stokr-platform/stokr-lite/backend/target/stokr-lite-1.0.0-SNAPSHOT.jar 2>/dev/null")
if 'stokr-lite' in jar:
    print("JAR built!")
    c("fuser -k 8080/tcp 2>/dev/null; docker rm -f stokr-lite-backend 2>/dev/null")
    c("nohup java -jar /root/stokr-platform/stokr-lite/backend/target/stokr-lite-1.0.0-SNAPSHOT.jar > /var/log/stokr.log 2>&1 &")
    time.sleep(35)
    print("Health:", c("curl -s -m3 http://localhost:8080/actuator/health 2>/dev/null || echo 'starting...'"))
else:
    print("BUILD FAILED. Checking errors:")
    print(c("cd /root/stokr-platform/stokr-lite/backend && mvn compile -DskipTests 2>&1 | grep 'ERROR' | head -5"))
s.close()

