# -*- coding: utf-8 -*-
import json, hashlib, time, urllib.request

IMAGE = "https://autoglm-oss.z.ai/auto_fly/2oy807_20260708-165233-d9df35c5-93e-clipboard-1783509746054.png?auth_key=1783509840-0-0-0b3f8dfa4b48bff933e625fd150dbba9"

ts = str(int(time.time()))
sign = hashlib.md5(f"100003&{ts}&38d2391985e2369a5fb8227d8e6cd5e5".encode()).hexdigest()

data = json.dumps({
    "prompt": "Describe every detail of this screenshot. What web page, app interface, or dashboard is this? List all visible tabs, sections, buttons, labels, error messages, numbers, and empty areas. What is the user likely pointing at as missing?",
    "image_url": IMAGE
}).encode('utf-8')

req = urllib.request.Request(
    "https://autoglm-api.autoglm.ai/agentdr/v1/assistant/skills/image-recognition",
    data=data,
    headers={
        "Content-Type": "application/json",
        "X-Auth-Appid": "100003",
        "X-Auth-TimeStamp": ts,
        "X-Auth-Sign": sign
    }
)

with urllib.request.urlopen(req, timeout=300) as resp:
    result = json.loads(resp.read().decode('utf-8'))

text = result.get("data", {}).get("text", "NO TEXT")

with open(r"C:\Users\itsvi\Desktop\work_new\stokr-platform\img_analysis.txt", "w", encoding="utf-8") as f:
    f.write(text)

print("OK - written to img_analysis.txt")
print(text[:500])
