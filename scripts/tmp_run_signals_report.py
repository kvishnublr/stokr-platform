#!/usr/bin/env python3
import paramiko
from pathlib import Path

sql = Path(__file__).with_name("today_signals_report.sql").read_text(encoding="utf-8")
c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)
sftp = c.open_sftp()
remote = "/tmp/today_signals_report.sql"
with sftp.file(remote, "w") as f:
    f.write(sql)
sftp.close()
_, o, e = c.exec_command(
    f"docker cp {remote} stokr-postgres:/tmp/today_signals_report.sql && "
    "docker exec stokr-postgres psql -U postgres -d stokr_platform -f /tmp/today_signals_report.sql",
    timeout=120,
)
print((o.read() + e.read()).decode())
_, o, e = c.exec_command("TZ=Asia/Kolkata date")
print("Report IST:", (o.read() + e.read()).decode().strip())
c.close()
