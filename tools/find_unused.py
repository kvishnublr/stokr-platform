import subprocess
import os

src_dir = 'stokr-ui/src'
check_dirs = ['lib', 'state', 'api', 'admin']

all_files = []
for d in check_dirs:
    for root, dirs, files in os.walk(f'{src_dir}/{d}'):
        for f in files:
            if f.endswith('.ts') or f.endswith('.tsx'):
                all_files.append(os.path.join(root, f))

basename_map = {}
for f in all_files:
    basename = os.path.basename(f).replace('.ts', '').replace('.tsx', '')
    basename_map.setdefault(basename, []).append(f)

result = subprocess.run(
    ['grep', '-rn', '-E', '^import .* from', src_dir],
    capture_output=True, text=True
)

all_imports = set()
for line in result.stdout.strip().split('\n'):
    if ':' not in line:
        continue
    parts = line.split('from', 1)
    if len(parts) < 2:
        continue
    imp = parts[1].strip().rstrip(';').strip("'\"")
    if '/' in imp:
        last_part = imp.rsplit('/', 1)[-1]
    else:
        last_part = imp
    all_imports.add(last_part)

for basename, paths in sorted(basename_map.items()):
    if basename not in all_imports:
        for path in paths:
            print(path)
