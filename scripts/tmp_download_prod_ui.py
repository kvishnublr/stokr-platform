#!/usr/bin/env python3
import os, paramiko
host = "173.249.55.84"
user = "root"
pw = os.environ.get("STOKR_PROD_SSH_PASS", "Temp1234..")
local_dir = os.path.join(os.path.dirname(__file__), "..", "tmp_prod_ui")
os.makedirs(local_dir, exist_ok=True)
ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect(host, username=user, password=pw, timeout=20)
sftp = ssh.open_sftp()
for remote, name in [
    ("/var/www/stokr/new/trader/index.html", "trader_index.html"),
    ("/var/www/stokr/new/admin/index.html", "admin_index.html"),
]:
    sftp.get(remote, os.path.join(local_dir, name))
    print("downloaded", name)
sftp.close()
ssh.close()
