import subprocess, sys, os
os.environ['PYTHONIOENCODING'] = 'utf-8'

r = subprocess.run([
    sys.executable,
    r"C:\Users\itsvi\.openclaw-autoclaw\skills\autoglm-image-recognition\image-recognition.py",
    "https://autoglm-oss.z.ai/auto_fly/2oy807_20260708-165233-d9df35c5-93e-clipboard-1783509746054.png?auth_key=1783509840-0-0-0b3f8dfa4b48bff933e625fd150dbba9",
    "Describe this screenshot in detail. What UI does it show - is it a web dashboard? What elements, tabs, or sections are visible? What might be missing?"
], capture_output=True, text=True)

out = r.stdout + r.stderr
# Write to file to avoid cp1252
with open(r"C:\Users\itsvi\Desktop\work_new\stokr-platform\img_result.txt", "w", encoding="utf-8") as f:
    f.write(out)
print("Written to img_result.txt")
print(out[:3000])
