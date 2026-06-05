import hashlib, sys
pw = sys.argv[1] if len(sys.argv) > 1 else "admin123"
print(hashlib.sha256(pw.encode()).hexdigest())
