import paramiko, sys

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())

try:
    ssh.connect("173.249.55.84", username="root", key_filename=r"C:\Users\itsvi\.ssh\id_rsa_stokr", timeout=15)

    # Step 1: Discover schema
    print("===== Discovering strategy_definitions columns =====")
    stdin, stdout, stderr = ssh.exec_command(
        'docker exec stokr-postgres psql -U postgres -d stokr_platform -c "SELECT column_name, data_type FROM information_schema.columns WHERE table_name = \'strategy_definitions\' ORDER BY ordinal_position;"',
        timeout=30
    )
    err = stderr.read().decode().strip()
    if err:
        print(f"STDERR: {err}")
    print(stdout.read().decode().strip())

    # Step 2: Discover strategy_instances columns
    print("\n===== Discovering strategy_instances columns =====")
    stdin, stdout, stderr = ssh.exec_command(
        'docker exec stokr-postgres psql -U postgres -d stokr_platform -c "SELECT column_name, data_type FROM information_schema.columns WHERE table_name = \'strategy_instances\' ORDER BY ordinal_position;"',
        timeout=30
    )
    err = stderr.read().decode().strip()
    if err:
        print(f"STDERR: {err}")
    print(stdout.read().decode().strip())

    # Step 3: Look for runtime binding tables
    print("\n===== Finding runtime binding tables =====")
    stdin, stdout, stderr = ssh.exec_command(
        'docker exec stokr-postgres psql -U postgres -d stokr_platform -c "SELECT table_name FROM information_schema.tables WHERE table_name LIKE \'%runtime%\' OR table_name LIKE \'%binding%\' ORDER BY table_name;"',
        timeout=30
    )
    err = stderr.read().decode().strip()
    if err:
        print(f"STDERR: {err}")
    print(stdout.read().decode().strip())

    ssh.close()
except Exception as e:
    print(f"ERROR: {e}")
    sys.exit(1)
