import paramiko

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("173.249.55.84", username="root", key_filename=r"C:\Users\itsvi\.ssh\id_rsa_stokr", timeout=15)

def pg(sql):
    # Use echo piped to psql to avoid quote issues
    cmd = f'echo "{sql}" | docker exec -i stokr-postgres psql -U stokr -d stokr_lite'
    _, stdout, stderr = ssh.exec_command(cmd)
    return stdout.read().decode().strip() or stderr.read().decode().strip()

# Insert trader_config for missing users
print("=== Insert trader_configs for users 2,3,4 ===")
sql = r"INSERT INTO trader_configs (user_id, mode, capital, max_positions, min_share_price, max_share_price, stop_loss_pct, target_pct, max_daily_loss, min_trade_gap_minutes, max_consecutive_losses, enabled) SELECT u.id, 'PAPER', 15000, 3, 200, 3000, 0.2, 0.6, 225, 2, 3, true FROM users u WHERE NOT EXISTS (SELECT 1 FROM trader_configs tc WHERE tc.user_id = u.id);"
print(pg(sql))

print("\n=== trader_configs after insert ===")
print(pg("SELECT id, user_id, mode, capital, max_daily_loss FROM trader_configs ORDER BY user_id;"))

print("\n=== deployments broker_account_id nullable ===")
print(pg("SELECT column_name, is_nullable FROM information_schema.columns WHERE table_name = 'deployments' AND column_name = 'broker_account_id';"))

# Also fix the Deployment.java entity
print("\n=== Check Deployment entity ===")
_, stdout, _ = ssh.exec_command("grep -n 'broker_account_id\\|nullable\\|brokerAccountId' /root/stokr-lite/backend/src/main/java/com/stokr/engine/Deployment.java 2>/dev/null | head -10")
print(stdout.read().decode().strip())

ssh.close()
