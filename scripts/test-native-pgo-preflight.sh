#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd -P)"
work="$(mktemp -d)"
trap 'rm -rf -- "$work"' EXIT
cat >"$work/native-image" <<'EOF'
#!/usr/bin/env bash
case "${1:-}" in
  --version) printf 'GraalVM Mock Native Image 0\n'; exit 0 ;;
  --help-extra)
    if [[ "${MOCK_PGO:-full}" == full ]]; then
      printf '%s\n' '  --pgo-instrument --pgo=<file>'
    else
      printf '%s\n' '  --initialize-at-build-time'
    fi
    exit 0 ;;
  *) exit 2 ;;
esac
EOF
chmod +x "$work/native-image"
if "$root/scripts/native-pgo-preflight.sh" "$work/native-image" >"$work/probe"; then
  grep -q CLI_CANDIDATE_ONLY "$work/probe"
else
  echo 'Expected a PGO flag candidate result' >&2; exit 1
fi
set +e
MOCK_PGO=absent "$root/scripts/native-pgo-preflight.sh" "$work/native-image" >"$work/no" 2>"$work/noerr"
status=$?
set -e
[[ "$status" == 78 ]] || { echo "Expected unsupported status 78, got $status" >&2; exit 1; }
grep -q 'UNSUPPORTED' "$work/noerr"
[[ ! -s "$work/no" ]] || { echo 'Unsupported probe emitted success data' >&2; exit 1; }
set +e
"$root/scripts/native-pgo-preflight.sh" "$work/nonexistent" >/dev/null 2>&1
status=$?
set -e
[[ "$status" == 78 ]] || { echo 'Missing executable accepted' >&2; exit 1; }
set +e
"$root/scripts/native-pgo-preflight.sh" native-image >/dev/null 2>&1
status=$?
set -e
[[ "$status" == 64 ]] || { echo 'Relative executable path accepted' >&2; exit 1; }
echo 'Native Image PGO eligibility probe tests passed (mock CLI only).'
