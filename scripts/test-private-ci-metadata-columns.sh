#!/usr/bin/env bash
# Source-free proof that repository metadata is exactly six TSV fields.
set -euo pipefail
dir="$(cd -- "$(dirname -- "$0")" && pwd -P)"
tmp="$(mktemp -d)"
trap 'rm -rf -- "$tmp"' EXIT
mkdir -p "$tmp/scripts" "$tmp/.github" "$tmp/bin"
cp "$dir/check-private-ci-destination.sh" "$tmp/scripts/"
printf '%s\n' 'sonus-auris-test/clients-api-contract 1323599063' > "$tmp/.github/private-ci-approved-repositories.txt"
cat > "$tmp/bin/gh" <<'MOCK'
#!/usr/bin/env bash
[[ "${GH_HOST:-}" == github.com && "${GH_PROMPT_DISABLED:-}" == 1 ]] || exit 2
[[ $# -eq 8 && "$1" == api && "$2" == --hostname && "$3" == github.com && "$4" == --method && "$5" == GET && "$6" == repos/sonus-auris-test/clients-api-contract && "$7" == --jq ]] || exit 2
printf '%s\n' "$FAKE_GH_METADATA"
MOCK
chmod +x "$tmp/bin/gh"
export PATH="$tmp/bin:$PATH"
tab="$(printf '\t')"
name=sonus-auris-test/clients-api-contract
valid="${name}${tab}private${tab}true${tab}1323599063${tab}false${tab}false"
FAKE_GH_METADATA="$valid" bash "$tmp/scripts/check-private-ci-destination.sh" "$name" >/dev/null
reject() {
  local value="$1"
  if FAKE_GH_METADATA="$value" bash "$tmp/scripts/check-private-ci-destination.sh" "$name" >/dev/null 2>&1; then
    printf 'unexpected metadata admission\n' >&2
    exit 1
  fi
}
reject "${name}${tab}${tab}private${tab}true${tab}1323599063${tab}false${tab}false"
reject "${name}${tab}private${tab}${tab}true${tab}1323599063${tab}false${tab}false"
reject "${valid}${tab}"
reject "${valid}${tab}extra"
reject "${valid} "
reject "${name}${tab}private${tab}true${tab}1323599063${tab}true${tab}false"
reject "${name}${tab}private${tab}true${tab}1323599063${tab}false${tab}true"
reject "${name}${tab}private${tab}true${tab}1323599063${tab}null${tab}false"
reject "${name}${tab}private${tab}true${tab}1323599063${tab}false"
printf 'PASS: exact six-field metadata parser\n'
