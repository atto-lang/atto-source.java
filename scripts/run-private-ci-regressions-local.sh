#!/usr/bin/env bash
# Execute actual private-CI offline regression suites. Never publishes source.
set -euo pipefail
umask 077
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
python_bin="${PYTHON:-python3}"
repeats="${PRIVATE_CI_TEST_REPEATS:-3}"
if [[ ! "$repeats" =~ ^[1-9][0-9]?$ ]]; then
  echo 'PRIVATE_CI_INVALID_REPEATS: expected an integer from 1 to 99' >&2
  exit 2
fi
files=(check-private-ci-destination.sh test-private-ci-destination.sh
       run-in-private-ci-destination.sh publish-private-ci-source.py
       test-publish-private-ci-source.py test-private-ci-merged-main.py)
for file in "${files[@]}"; do
  if [[ ! -f "$script_dir/$file" || -L "$script_dir/$file" ]]; then
    printf 'PRIVATE_CI_MISSING_OR_SYMLINKED_FILE: %s\n' "$file" >&2
    exit 2
  fi
done
unset GH_TOKEN GITHUB_TOKEN GH_ENTERPRISE_TOKEN GITHUB_API_TOKEN
unset GIT_ASKPASS SSH_ASKPASS SSH_AUTH_SOCK
export GH_PROMPT_DISABLED=1 GIT_TERMINAL_PROMPT=0
config_sandbox="$(mktemp -d)"
manifest_before="$(mktemp)"
manifest_after="$(mktemp)"
trap 'rm -rf "$config_sandbox"; rm -f "$manifest_before" "$manifest_after"' EXIT
export GH_CONFIG_DIR="$config_sandbox" GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null
fingerprint() {
  "$python_bin" - "$script_dir" "${files[@]}" <<'PY'
import hashlib, pathlib, sys
root = pathlib.Path(sys.argv[1])
for name in sys.argv[2:]:
    path = root / name
    if path.is_symlink() or not path.is_file():
        raise SystemExit('PRIVATE_CI_FILE_CHANGED: ' + name)
    print(hashlib.sha256(path.read_bytes()).hexdigest() + '  ' + name)
PY
}
fingerprint > "$manifest_before"
echo 'PRIVATE_CI_TEST_SCOPE=OFFLINE_NO_PUBLISH'
"$python_bin" - "$script_dir" <<'PY'
import pathlib, sys
root = pathlib.Path(sys.argv[1])
for name in ('publish-private-ci-source.py', 'test-publish-private-ci-source.py',
             'test-private-ci-merged-main.py'):
    path = root / name
    compile(path.read_bytes(), str(path), 'exec')
print('PRIVATE_CI_PYTHON_COMPILE=3')
PY
bash -n "$script_dir/check-private-ci-destination.sh" \
    "$script_dir/test-private-ci-destination.sh" \
    "$script_dir/run-in-private-ci-destination.sh"
echo 'PRIVATE_CI_BASH_SYNTAX=3'
bash "$script_dir/test-private-ci-destination.sh"
for ((i=1; i<=repeats; i++)); do
  printf 'PRIVATE_CI_FULL_PYTHON_RUN=%d/%d\n' "$i" "$repeats"
  "$python_bin" -B "$script_dir/test-publish-private-ci-source.py"
done
fingerprint > "$manifest_after"
if ! cmp -s "$manifest_before" "$manifest_after"; then
  echo 'PRIVATE_CI_FILES_CHANGED_DURING_TEST' >&2
  exit 1
fi
echo 'PRIVATE_CI_COMPLETE_REGRESSIONS_PASSED=1'
cat "$manifest_after"
