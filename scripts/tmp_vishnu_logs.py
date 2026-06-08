import paramiko
uid = "6343e483-1d21-4fdf-ac0c-1ba19eaf2ff4"
c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)
cmds = [
    f"docker logs stokr-api --since 30m 2>&1 | grep -E '6343e483|vishnu|broker.truth|terminal.workstation' | tail -30",
    f"""docker exec stokr-postgres psql -U postgres -d stokr_platform -c \"SELECT symbol, quantity FROM portfolio_positions WHERE user_id='{uid}' AND deleted=false AND quantity!=0 LIMIT 20;\" """,
    f"""docker exec stokr-postgres psql -U postgres -d stokr_platform -c \"SELECT compute FROM (SELECT 1) x;\" """,
]
# live net qty from oms - skip
for cmd in cmds[:2]:
    _, o, e = c.exec_command(cmd)
    print("===", cmd[:60], "===")
    print((o.read()+e.read()).decode())
c.close()
