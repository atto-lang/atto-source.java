#!/usr/bin/env bash
# Source-free, credential-free regression for protected private-export ownership.
set -euo pipefail
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
policy="${1:-$root/.github/CODEOWNERS}"

# Extract effective CODEOWNERS rules without ignoring an unexpected owner:
# GitHub uses the final matching rule, so exact security rules must be last.
effective_rules() {
  awk '
    /^[[:space:]]*($|#)/ { next }
    {
      out = $1
      for (i = 2; i <= NF; ++i) out = out " " $i
      print out
    }
  ' "$1"
}

expected_tail() {
  cat <<'RULES'
/.github/CODEOWNERS @ORESoftware
/.github/private-ci-approved-repositories.txt @ORESoftware
/.github/private-ci-incident-dispositions.json @ORESoftware
/.github/private-ci-carrier-contracts.json @ORESoftware
/scripts/check-private-ci-destination.sh @ORESoftware
/scripts/run-in-private-ci-destination.sh @ORESoftware
/scripts/publish-private-ci-source.py @ORESoftware
/scripts/publish-private-ci-source-v3.py @ORESoftware
RULES
}

check() {
  local input="$1"
  [[ -f "$input" && ! -L "$input" ]] || return 1
  cmp -s <(effective_rules "$input" | tail -n 8) <(expected_tail)
}

if ! check "$policy"; then
  echo 'FAIL: CODEOWNERS lacks final sole-@ORESoftware private-export rules' >&2
  exit 1
fi

work="$(mktemp -d)"
trap 'rm -rf -- "$work"' EXIT
cp -- "$policy" "$work/good"
check "$work/good" || { echo 'FAIL: fixture baseline' >&2; exit 1; }

reject() {
  if check "$1"; then
    echo "FAIL: tampered CODEOWNERS accepted: $2" >&2
    exit 1
  fi
}

# Appending any owner/override after the security rules must be denied.
cp -- "$work/good" "$work/append"
printf '/scripts/ @the1mills\n' >> "$work/append"
reject "$work/append" 'postfix overriding rule'

# Mixed author and security owner on an exact line is not independent review.
while IFS= read -r exact; do
  awk -v exact="$exact" '{
    out = $1
    for (i = 2; i <= NF; ++i) out = out " " $i
    if (out == exact) print out " @the1mills"
    else print $0
  }' "$work/good" > "$work/mixed"
  reject "$work/mixed" "mixed owner for $exact"
done < <(expected_tail)

# Missing/reordered security rules and a final broad rule fail closed.
awk '$1 != "/scripts/publish-private-ci-source-v3.py" { print }' \
  "$work/good" > "$work/missing"
reject "$work/missing" 'missing security path'
awk '{ lines[NR] = $0 } END {
  for (i = 1; i <= NR - 2; ++i) print lines[i]
  print lines[NR]
  print lines[NR - 1]
}' "$work/good" > "$work/reordered"
reject "$work/reordered" 'reordered exact rules'

# Fail closed for substituted/symlinked inputs.
ln -s "$work/good" "$work/link"
reject "$work/link" 'symlinked CODEOWNERS'

printf 'PASS: terminal private-export ownership and 12 adversarial cases\n'
