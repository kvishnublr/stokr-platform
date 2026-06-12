import paramiko

c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)
_, o, e = c.exec_command("docker exec stokr-api printenv SPRING_DATASOURCE_URL SPRING_DATASOURCE_USERNAME")
print((o.read() + e.read()).decode())
_, o, e = c.exec_command("curl -sf http://127.0.0.1:8080/api/admin/broker-infrastructure/ZERODHA 2>/dev/null | head -c 800")
print("=== broker infra (may need auth) ===")
print((o.read() + e.read()).decode() or "(empty)")
c.close()
