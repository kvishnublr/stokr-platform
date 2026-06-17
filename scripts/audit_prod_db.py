import paramiko, sys

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())

try:
    ssh.connect("173.249.55.84", username="root", key_filename=r"C:\Users\itsvi\.ssh\id_rsa_stokr", timeout=15)

    queries = [
        ("Query 6: ALL strategy definitions including deleted",
         "SELECT id, strategy_key, display_name, description, category, asset_class, execution_mode, deleted FROM strategy_definitions ORDER BY strategy_key;"),
        ("Query 7: All strategy instances",
         "SELECT si.id, si.definition_id AS strategy_id, sd.strategy_key, si.runtime_state AS status, si.enabled AS running FROM strategy_instances si JOIN strategy_definitions sd ON si.definition_id = sd.id ORDER BY sd.strategy_key;"),
        ("Query 8: All runtime bindings (via strategy_runtime_bindings + universe_groups)",
         "SELECT rb.id, rb.strategy_catalog_id AS strategy_id, sd.strategy_key, ug.group_key AS universe, rb.scan_interval_seconds, rb.runtime_enabled AS active FROM strategy_runtime_bindings rb JOIN strategy_definitions sd ON rb.strategy_catalog_id = sd.id JOIN strategy_universe_groups ug ON rb.universe_group_id = ug.id ORDER BY sd.strategy_key, ug.group_key;")
    ]

    for label, sql in queries:
        print(f"\n===== {label} =====")
        cmd = 'docker exec stokr-postgres psql -U postgres -d stokr_platform -c "' + sql + '"'
        stdin, stdout, stderr = ssh.exec_command(cmd, timeout=30)
        err = stderr.read().decode().strip()
        if err:
            print(f"STDERR: {err}")
        out = stdout.read().decode().strip()
        print(out)

    ssh.close()
except Exception as e:
    print(f"ERROR: {e}")
    sys.exit(1)
