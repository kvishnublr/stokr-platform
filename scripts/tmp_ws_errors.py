import paramiko
c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)
_, o, e = c.exec_command("docker logs stokr-api --since 45m 2>&1 | grep -E 'terminal/workstation|ClassCastException|broker_truth_sync_failed|Unhandled error' | tail -25")
print((o.read()+e.read()).decode())
c.close()
