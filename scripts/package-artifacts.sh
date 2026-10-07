#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
output_dir="${PHONE_AGENT_OUTPUT_DIR:-$project_dir/../outputs}"
mkdir -p "$output_dir"
cp "$project_dir/app/build/outputs/apk/debug/app-debug.apk" "$output_dir/PhoneAgent-0.1.0-debug.apk"
python3 - "$project_dir" "$output_dir" <<'PY'
from pathlib import Path
import hashlib
import sys
import zipfile
project, output = map(Path, sys.argv[1:])
excluded = {'.git', '.gradle', '.kotlin', '.idea', 'build', 'artifacts'}
with zipfile.ZipFile(output / 'PhoneAgent-0.1.0-source.zip', 'w', zipfile.ZIP_DEFLATED) as archive:
    for path in sorted(project.rglob('*')):
        relative = path.relative_to(project)
        if not path.is_file() or any(part in excluded for part in relative.parts):
            continue
        if path.name == 'local.properties' or path.suffix in {'.jks', '.keystore', '.iml'}:
            continue
        archive.write(path, 'android-ai-assistant/' + relative.as_posix())
with (output / 'SHA256SUMS.txt').open('w') as sums:
    for name in ['PhoneAgent-0.1.0-debug.apk', 'PhoneAgent-0.1.0-source.zip']:
        digest = hashlib.sha256((output / name).read_bytes()).hexdigest()
        sums.write(f'{digest}  {name}\n')
print(output)
PY
