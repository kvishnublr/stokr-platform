import paramiko
c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)

def run(cmd):
    print(">>>", cmd[:120])
    _, o, e = c.exec_command(cmd, timeout=90)
    out = (o.read() + e.read()).decode("utf-8", "replace")
    print(out[-6000:])

run("docker ps -a --filter name=stokr-api --format '{{.Status}}'")
run("curl -sS -m 10 -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/api/auth/login; echo")
run("docker logs stokr-api 2>&1 | tail -30")
run("docker logs stokr-api 2>&1 | grep -i safe_startup | tail -10")
c.close()
