import paramiko, time, sys

password = sys.argv[1] if len(sys.argv) > 1 else None
if not password:
    print("Need password"); sys.exit(1)

try:
    s = paramiko.SSHClient()
    s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    s.connect('173.249.55.84', username='root', password=password, timeout=20)
    
    stdin, stdout, stderr = s.exec_command("docker ps --format '{{.Names}} {{.Status}}' 2>&1")
    time.sleep(2)
    print(stdout.read().decode())
    
    stdin2, stdout2, stderr2 = s.exec_command(
        "su - postgres -c \"psql -d stokr_lite -t -c 'SELECT name, strategy_type, enabled FROM strategies ORDER BY id DESC LIMIT 5;'\""
    )
    time.sleep(2)
    print("\nStrategies:")
    print(stdout2.read().decode())
    
    s.close()
except Exception as e:
    print(f"Error: {e}")
