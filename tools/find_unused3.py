import subprocess
import os
import re

src_dir = 'stokr-ui/src'

# All dirs to check for unused files
check_dirs = ['lib', 'state', 'api', 'admin', 'hooks', 'services', 'types']

all_files = []
for d in check_dirs:
    for root, dirs, files in os.walk(f'{src_dir}/{d}'):
        for f in files:
            if f.endswith('.ts') or f.endswith('.tsx'):
                all_files.append(os.path.join(root, f))

# Get ALL import statements in the project
import_result = subprocess.run(
    ['grep', '-rno', '-E', "from ['\"][^'\"]+['\"]", src_dir],
    capture_output=True, text=True
)

# Parse imports: file -> set of imported paths
imports_by_file = {}
for line in import_result.stdout.strip().split('\n'):
    if not line:
        continue
    parts = line.split(':')
    if len(parts) < 3:
        continue
    imp_file = parts[0]
    imp_path = line[line.index("from '")+6:] if "from '" in line else line[line.index('from "')+6:]
    imp_path = imp_path.strip("'").strip('"')
    imports_by_file.setdefault(imp_file, set()).add(imp_path)

# For each check_file, see if it's imported
print('=== UNUSED FILES ===')
for path in sorted(all_files):
    rel = path.replace('\\', '/')
    basename_no_ext = os.path.basename(rel).replace('.ts', '').replace('.tsx', '')
    
    used = False
    for imp_file, imps in imports_by_file.items():
        if imp_file == rel:
            continue
        for imp in imps:
            imp_basename = imp.rsplit('/', 1)[-1] if '/' in imp else imp
            if imp_basename == basename_no_ext or imp == basename_no_ext:
                used = True
                break
        if used:
            break
    
    if not used:
        print(rel)
