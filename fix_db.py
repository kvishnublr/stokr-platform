import paramiko

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("173.249.55.84", username="root", key_filename=r"C:\Users\itsvi\.ssh\id_rsa_stokr", timeout=15)

def pg(sql):
    cmd = f"""docker exec stokr-postgres psql -U stokr -d stokr_lite -c '{sql}'"""
    _, stdout, stderr = ssh.exec_command(cmd)
    return stdout.read().decode().strip() or stderr.read().decode().strip()

# Insert trader_config for all users that don't have one
print("=== Insert trader_configs for all users ===")
for uid in [1, 2, 3, 4]:
    sql = f"INSERT INTO trader_configs (user_id, mode, capital, max_positions, min_share_price, max_share_price, stop_loss_pct, target_pct, max_daily_loss, min_trade_gap_minutes, max_consecutive_losses, enabled) SELECT {uid}, 'PAPER', 15000, 3, 200, 3000, 0.2, 0.6, 225, 2, 3, true WHERE NOT EXISTS (SELECT 1 FROM trader_configs WHERE user_id = {uid});"
    print(f"  user {uid}:", pg(sql))

print("\n=== trader_configs after insert ===")
print(pg("SELECT id, user_id, mode, capital, max_positions, max_daily_loss FROM trader_configs ORDER BY user_id;"))

# Check deployments schema after fix
print("\n=== deployments - broker nullable check ===")
print(pg("SELECT column_name, is_nullable FROM information_schema.columns WHERE table_name = 'deployments' AND column_name = 'broker_account_id';"))

# Check Deployment JPA entity
print("\n=== Check frontend pages for positions/trades URLs ===")
_, stdout, _ = ssh.exec_command("grep -r '/api/positions\\|/api/trades' /root/stokr-lite/backend/src/ 2>/dev/null | head -10")
print(stdout.read().decode().strip() or "None found in backend")

ssh.close()
print("\nDone.")
