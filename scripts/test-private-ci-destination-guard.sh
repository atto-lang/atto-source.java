#!/usr/bin/env bash
# Source-free, offline admission test. A temporary allowlist here is NOT an
# approval to export source: production v3 additionally authenticates protected
# source policy, CODEOWNERS review, immutable IDs, and both repository trees.
set -euo pipefail

root="$(cd -- "$(dirname -- "$0")/.." && pwd -P)"
guard="$root/scripts/check-private-ci-destination.sh"
bash -n "$guard"

tmp="$(mktemp -d)"
trap 'rm -rf -- "$tmp"' EXIT
mkdir -p "$tmp/scripts" "$tmp/.github" "$tmp/bin"
cp -- "$guard" "$tmp/scripts/check-private-ci-destination.sh"
policy="$tmp/.github/private-ci-approved-repositories.txt"
target="sonus-auris-test/clients-api-contract"
approved="$target 1323599063"

# No network, authenticated sessions, tokens, real policy edits or source data.
cat > "$tmp/bin/gh" <<'MOCK'
#!/usr/bin/env bash
[[ $# -ge 2 && "$1" == api && "$2" == repos/sonus-auris-test/clients-api-contract ]] || exit 2
printf '%s\n' "$FAKE_GH_METADATA"
MOCK
chmod 700 "$tmp/bin/gh"
export PATH="$tmp/bin:$PATH"
valid=$'sonus-auris-test/clients-api-contract\tprivate\ttrue\t1323599063'

reject() {
  local label="$1" repo="$2" metadata="$3"
  if FAKE_GH_METADATA="$metadata" bash "$tmp/scripts/check-private-ci-destination.sh" \
      "$repo" >"$tmp/output" 2>&1; then
    printf 'unexpected admission: %s\n' "$label" >&2
    exit 1
  fi
  grep -q '^PRIVATE_CI_DESTINATION_REJECTED:' "$tmp/output" || {
    printf 'failure was not fail-closed for case: %s\n' "$label" >&2
    exit 1
  }
}

: > "$policy"
reject "default empty policy" "$target" "$valid"
printf '%s\n' "$approved" > "$policy"
reject "invalid slug" "../clients-api-contract" "$valid"
reject "different repository name" "other-org/clients-api-contract" "$valid"
reject "wrong immutable ID" "$target" \
  $'sonus-auris-test/clients-api-contract\tprivate\ttrue\t999'
reject "public visibility" "$target" \
  $'sonus-auris-test/clients-api-contract\tpublic\tfalse\t1323599063'
reject "incorrect reported owner/repo" "$target" \
  $'other/clients-api-contract\tprivate\ttrue\t1323599063'
reject "multiline response" "$target" "$valid"$'\ninjected'
reject "missing metadata field" "$target" \
  

printf '%s\n' "$approved" "$approved" > "$policy"
reject "duplicate approval" "$target" "$valid"
printf '%s\n' "$approved extra" > "$policy"
reject "suffix appended to policy ID" "$target" "$valid"
printf '%s\n' "$approved" > "$policy"

FAKE_GH_METADATA="$valid" bash "$tmp/scripts/check-private-ci-destination.sh" \
  "$target" >"$tmp/output"
grep -q '^PRIVATE_CI_DESTINATION_APPROVED:' "$tmp/output"
printf 'source-free private CI guard checks passed\n'
sonus-auris-test/clients-api-contract\tprivate\ttrue'
reject "empty visibility field" "$target" \
  

printf '%s\n' "$approved" "$approved" > "$policy"
reject "duplicate approval" "$target" "$valid"
printf '%s\n' "$approved extra" > "$policy"
reject "suffix appended to policy ID" "$target" "$valid"
printf '%s\n' "$approved" > "$policy"

FAKE_GH_METADATA="$valid" bash "$tmp/scripts/check-private-ci-destination.sh" \
  "$target" >"$tmp/output"
grep -q '^PRIVATE_CI_DESTINATION_APPROVED:' "$tmp/output"
printf 'source-free private CI guard checks passed\n'
sonus-auris-test/clients-api-contract\t\tprivate\ttrue\t1323599063'
reject "empty boolean field" "$target" \
  

printf '%s\n' "$approved" "$approved" > "$policy"
reject "duplicate approval" "$target" "$valid"
printf '%s\n' "$approved extra" > "$policy"
reject "suffix appended to policy ID" "$target" "$valid"
printf '%s\n' "$approved" > "$policy"

FAKE_GH_METADATA="$valid" bash "$tmp/scripts/check-private-ci-destination.sh" \
  "$target" >"$tmp/output"
grep -q '^PRIVATE_CI_DESTINATION_APPROVED:' "$tmp/output"
printf 'source-free private CI guard checks passed\n'
sonus-auris-test/clients-api-contract\tprivate\t\ttrue\t1323599063'
reject "empty trailing field" "$target" \
  

printf '%s\n' "$approved" "$approved" > "$policy"
reject "duplicate approval" "$target" "$valid"
printf '%s\n' "$approved extra" > "$policy"
reject "suffix appended to policy ID" "$target" "$valid"
printf '%s\n' "$approved" > "$policy"

FAKE_GH_METADATA="$valid" bash "$tmp/scripts/check-private-ci-destination.sh" \
  "$target" >"$tmp/output"
grep -q '^PRIVATE_CI_DESTINATION_APPROVED:' "$tmp/output"
printf 'source-free private CI guard checks passed\n'
sonus-auris-test/clients-api-contract\tprivate\ttrue\t1323599063\t'
reject "extra metadata field" "$target" \
  

printf '%s\n' "$approved" "$approved" > "$policy"
reject "duplicate approval" "$target" "$valid"
printf '%s\n' "$approved extra" > "$policy"
reject "suffix appended to policy ID" "$target" "$valid"
printf '%s\n' "$approved" > "$policy"

FAKE_GH_METADATA="$valid" bash "$tmp/scripts/check-private-ci-destination.sh" \
  "$target" >"$tmp/output"
grep -q '^PRIVATE_CI_DESTINATION_APPROVED:' "$tmp/output"
printf 'source-free private CI guard checks passed\n'
sonus-auris-test/clients-api-contract\tprivate\ttrue\t1323599063\textra'
reject "trailing metadata space" "$target" \
  

printf '%s\n' "$approved" "$approved" > "$policy"
reject "duplicate approval" "$target" "$valid"
printf '%s\n' "$approved extra" > "$policy"
reject "suffix appended to policy ID" "$target" "$valid"
printf '%s\n' "$approved" > "$policy"

FAKE_GH_METADATA="$valid" bash "$tmp/scripts/check-private-ci-destination.sh" \
  "$target" >"$tmp/output"
grep -q '^PRIVATE_CI_DESTINATION_APPROVED:' "$tmp/output"
printf 'source-free private CI guard checks passed\n'
sonus-auris-test/clients-api-contract\tprivate\ttrue\t1323599063 '

printf '%s\n' "$approved" "$approved" > "$policy"
reject "duplicate approval" "$target" "$valid"
printf '%s\n' "$approved extra" > "$policy"
reject "suffix appended to policy ID" "$target" "$valid"
printf '%s\n' "$approved" > "$policy"

FAKE_GH_METADATA="$valid" bash "$tmp/scripts/check-private-ci-destination.sh" \
  "$target" >"$tmp/output"
grep -q '^PRIVATE_CI_DESTINATION_APPROVED:' "$tmp/output"
printf 'source-free private CI guard checks passed\n'
