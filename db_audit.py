import paramiko

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("173.249.55.84", username="root", key_filename=r"C:\Users\itsvi\.ssh\id_rsa_stokr", timeout=15)

def run(cmd):
    _, stdout, stderr = ssh.exec_command(cmd)
    out = stdout.read().decode().strip()
    err = stderr.read().decode().strip()
    return out or err

def pg(sql):
    cmd = f"""docker exec stokr-postgres psql -U stokr -d stokr_lite -c '{sql}'"""
    return run(cmd)

print("=== deployments schema ===")
print(pg("SELECT column_name, is_nullable FROM information_schema.columns WHERE table_name='deployments' ORDER BY ordinal_position;"))

print("\n=== Fix: broker_account_id nullable ===")
print(pg("ALTER TABLE deployments ALTER COLUMN broker_account_id DROP NOT NULL;"))

print("\n=== trader_configs rows ===")
print(pg("SELECT id, user_id, mode, capital, max_positions, max_daily_loss FROM trader_configs;"))

print("\n=== Insert trader_config for user 4 ===")
print(pg("INSERT INTO trader_configs (user_id, mode, capital, max_positions, min_share_price, max_share_price, stop_loss_pct, target_pct, max_daily_loss, min_trade_gap_minutes, max_consecutive_losses, enabled) SELECT 4, 'PAPER', 15000, 3, 200, 3000, 0.2, 0.6, 225, 2, 3, true WHERE NOT EXISTS (SELECT 1 FROM trader_configs WHERE user_id = 4);"))

print("\n=== chartink_positions schema ===")
print(pg("SELECT column_name, is_nullable FROM information_schema.columns WHERE table_name='chartink_positions' ORDER BY ordinal_position;"))

print("\n=== All users ===")
print(pg("SELECT id, email, role, enabled FROM users;"))

print("\n=== strategies in DB ===")
print(pg("SELECT id, name, strategy_type, enabled FROM strategies ORDER BY id;"))

ssh.close()
