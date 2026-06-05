import sys, json
data = json.load(sys.stdin)
print("Token present:", "accessToken" in data)
if "accessToken" in data:
    print("Token:", data["accessToken"][:50] + "...")
else:
    print("Keys:", list(data.keys()))
    print("Full response:", json.dumps(data, indent=2))
