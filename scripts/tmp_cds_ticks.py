#!/usr/bin/env python3
import paramiko
c=paramiko.SSHClient(); c.set_missing_host_key_policy(paramiko.AutoAddPolicy()); c.connect('173.249.55.84',username='root',password='Temp1234..',timeout=30)
for cmd in [
 "docker logs stokr-api 2>&1 | grep -i USDINR | tail -15",
 "docker exec stokr-postgres psql -U postgres -d stokr_platform -c \"SELECT COUNT(*) FROM marketdata_candles WHERE symbol='USDINR';\"",
 "docker logs stokr-api 2>&1 | grep integrityBlocked | tail -5",
]:
 print('\n===',cmd,'===\n')
 _,o,e=c.exec_command(cmd,timeout=60); print((o.read()+e.read()).decode())
c.close()
