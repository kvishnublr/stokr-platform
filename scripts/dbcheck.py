#!/usr/bin/env python3
import subprocess
RID = "6d31f48e-0b40-4041-8436-3373a26952ed"
OID = "1d4605ca-52b8-41c1-a128-a3d939a5d350"

def q(sql):
    r = subprocess.run(
        ["docker", "exec", "stokr-postgres", "psql", "-U", "postgres", "-d", "stokr_platform", "-c", sql],
        capture_output=True,
        text=True,
    )
    print(r.stdout.strip())

print("ORDER:", end=" ")
q(f"SELECT state FROM oms_orders WHERE id='{OID}';")
print("RUN:", end=" ")
q(f"SELECT final_status, square_off_status, auto_square_off_due_at, now() AT TIME ZONE 'UTC' AS utc_now FROM admin_test_signal_runs WHERE id='{RID}';")
print("POSITION:", end=" ")
q("SELECT quantity FROM portfolio_positions WHERE user_id='6343e483-1d21-4fdf-ac0c-1ba19eaf2ff4' AND symbol='INFY' AND deleted=false;")
