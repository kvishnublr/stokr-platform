import paramiko
s=paramiko.SSHClient();s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84',username='root',password='19119e3a6793dde1',timeout=30)
def c(cmd):
    i,o,e = s.exec_command(cmd)
    return o.read().decode(errors='replace').strip()
print(c("docker logs stokr-lite-backend 2>&1 | grep -A3 'Error\|Exception\|Caused by\|Unsatisfied\|Failed' | head -50"))
s.close()
