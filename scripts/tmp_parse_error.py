#!/usr/bin/env python3
import paramiko, json
c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)
_, o, e = c.exec_command("docker logs stokr-api 2>&1 | grep 'Application run failed' | tail -1", timeout=60)
line = (o.read() + e.read()).decode("utf-8", "replace").strip()
if not line:
    print("NO LINE")
else:
    obj = json.loads(line)
    st = obj.get("stack_trace", "")
    idx = 0
    while True:
        pos = st.find("Caused by:", idx)
        if pos == -1:
            break
        nxt = st.find("Caused by:", pos + 10)
        chunk = st[pos:nxt if nxt != -1 else pos + 1500]
        print(chunk)
        print("====")
        idx = pos + 10
c.close()
