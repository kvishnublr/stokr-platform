import subprocess
r = subprocess.run(["ssh", "-o", "ConnectTimeout=30", "root@173.249.55.84",
    "curl -s -X POST http://localhost:8081/api/auth/login -H 'Content-Type: application/json' -d '{\"email\":\"admin@stokr.in\",\"password\":\"`$ADMIN_PASSWORD\"}'"],
    capture_output=True, text=True, timeout=20)
print(r.stdout[:500])

