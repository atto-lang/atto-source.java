#!/usr/bin/env bash
# Review gate for new PRs after the AttoLang-first policy merge. No Python.
set -euo pipefail

if (( $# != 4 )); then
  echo 'usage: check_atto_first_policy.sh BASE_SHA HEAD_SHA PR_BODY_FILE PR_CREATED_AT' >&2
  exit 2
fi
base="$1"
head="$2"
body="$3"
created="$4"
cutoff='2026-11-04T18:36:18Z' # AttoLang-first directive merge (#2534).

for sha in "$base" "$head"; do
  if [[ ! "$sha" =~ ^[0-9a-f]{40}$ ]] || ! git cat-file -e "${sha}^{commit}"; then
    echo "atto-first: missing or invalid commit SHA: $sha" >&2
    exit 2
  fi
done
if [[ ! -f "$body" || ! "$created" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]]; then
  echo 'atto-first: missing PR body or malformed PR creation time' >&2
  exit 2
fi
if [[ "$created" < "$cutoff" ]]; then
  echo "atto-first: legacy PR opened before $cutoff; review manually"
  exit 0
fi

changed="$(mktemp)"
trap 'rm -f "$changed"' EXIT
git merge-base "$base" "$head" > /dev/null || { echo "atto-first: PR head and base have no common ancestor" >&2; exit 2; }
git diff --name-only --diff-filter=ACMR -z "$base...$head" > "$changed"
java_paths=()
while IFS= read -r -d '' path; do
  [[ "$path" == *.java ]] && java_paths+=("$path")
done < "$changed"
if (( ${#java_paths[@]} == 0 )); then
  echo 'atto-first: no new or modified Java source; policy gate passes'
  exit 0
fi

# Metadata cannot establish semantic correctness; it makes exceptions auditable.
field_value() {
  local label="$1" line
  # Reject conflicting and repeated authority fields; only one claim
  # per evidence label can be accepted for subsequent semantic review.
  line="$(grep -i -E "^[[:space:]]*${label}:[[:space:]]*" "$body" || true)"
  [[ -n "$line" && "$line" != *$'\n'* ]] || return 1
  printf '%s\n' "${line#*:}" | sed -E 's/^[[:space:]]*//;s/[[:space:]]*$//'
}
valid_field() {
  local label="$1" value
  value="$(field_value "$label")" || return 1
  (( ${#value} >= 12 )) || return 1
  [[ ! "${value,,}" =~ ^(todo|tbd|n/a|none|none\ yet|pending|placeholder|-)\.?$ ]]
}

missing=0
for field in 'AttoLang guest implementation' 'Java host/compiler exception' 'Guest test evidence'; do
  if ! valid_field "$field"; then
    echo "atto-first: required descriptive PR body field missing/placeholder: $field:" >&2
    missing=1
  fi
done
if (( missing != 0 )); then
  printf 'atto-first: %s Java file(s) changed; provide guest implementation, a specific host/compiler justification, and honest test evidence.\n' "${#java_paths[@]}" >&2
  printf 'atto-first: example: Java host/compiler exception: parser: extend .atto matching; Java only contains AST support.\n' >&2
  exit 1
fi
printf 'atto-first: %s Java path(s) disclosed; semantic review of the claimed exception is STILL REQUIRED\n' "${#java_paths[@]}"
printf '  %s\n' "${java_paths[@]}"
