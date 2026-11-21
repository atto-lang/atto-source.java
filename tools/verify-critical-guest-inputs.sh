#!/usr/bin/env bash
# Scoped Bash-only original-head input gate for the Java/Truffle guest workflow.
# Does not execute any AttoLang compiler or guest.
set -euo pipefail
: "${EXPECTED_SOURCE_HEAD:?original 40-hex head required}"
[[ "$EXPECTED_SOURCE_HEAD" =~ ^[0-9a-f]{40}$ ]] || { echo HEAD_INVALID >&2; exit 1; }
[[ "$(git rev-parse HEAD)" == "$EXPECTED_SOURCE_HEAD" ]] ||
  { echo HEAD_MISMATCH >&2; exit 1; }
[[ -z "$(git status --porcelain --untracked-files=no)" ]] ||
  { echo TRACKED_INPUT_DIRTY >&2; exit 1; }
roots=(src/main/java src/test/java src/main/resources src/test/resources .mvn)
required=(pom.xml tools/VerifyAssuranceSurefire.java
  examples/critical-actor-checked-v1.atto
  examples/critical-actor-checked-v1.ores
  examples/critical-actor-early-return-v1.atto
  examples/critical-actor-early-return-v1.ores
  .github/workflows/critical-suspends-java-admission.yml)
for f in "${required[@]}"; do
  [[ -f "$f" && ! -L "$f" ]] &&
    git ls-files --error-unmatch -- "$f" >/dev/null 2>&1 ||
    { echo "REQUIRED_INPUT_MISSING: $f" >&2; exit 1; }
done
# Capture both enumerations and require a complete index/tree path match.
# Process substitution alone would discard a failed enumerator's exit status.
index_manifest="$(mktemp)"
tree_manifest="$(mktemp)"
walk_manifest="$(mktemp)"
trap 'rm -f -- "$index_manifest" "$tree_manifest" "$walk_manifest"' EXIT
git ls-files -z -- "${roots[@]}" "${required[@]}" > "$index_manifest" ||
  { echo INDEX_ENUMERATION_FAILED >&2; exit 1; }
git ls-tree -r --name-only -z HEAD -- "${roots[@]}" "${required[@]}" > "$tree_manifest" ||
  { echo COMMITTED_TREE_ENUMERATION_FAILED >&2; exit 1; }
cmp -s "$index_manifest" "$tree_manifest" ||
  { echo INDEX_TREE_PATH_MISMATCH >&2; exit 1; }
for root in "${roots[@]}"; do
  if [[ -e "$root" || -L "$root" ]]; then
    [[ -d "$root" && ! -L "$root" ]] ||
      { echo "BAD_SOURCE_ROOT: $root" >&2; exit 1; }
    find -P "$root" \( -type f -o -type l \) -print0 > "$walk_manifest" ||
      { echo WALK_ENUMERATION_FAILED >&2; exit 1; }
    while IFS= read -r -d '' f; do
      [[ ! -L "$f" ]] &&
        git ls-files --error-unmatch -- "$f" >/dev/null 2>&1 ||
        { echo "UNTRACKED_INPUT: $f" >&2; exit 1; }
    done < "$walk_manifest"
  fi
done
n=0
while IFS= read -r -d '' f; do
  [[ -f "$f" && ! -L "$f" ]] ||
    { echo "NONREGULAR_INPUT: $f" >&2; exit 1; }
  expected="$(git rev-parse --verify "HEAD:$f")" ||
    { echo "MISSING_HEAD_BLOB: $f" >&2; exit 1; }
  actual="$(git hash-object --no-filters -- "$f")" ||
    { echo "UNREADABLE_INPUT: $f" >&2; exit 1; }
  [[ "$expected" == "$actual" ]] ||
    { echo "HEAD_BLOB_MISMATCH: $f" >&2; exit 1; }
  n=$((n+1))
done < "$index_manifest"
[[ "$n" -ge "${#required[@]}" ]] ||
  { echo INCOMPLETE_INPUT_SCAN >&2; exit 1; }
echo "SCOPED_JAVA_INPUTS_MATCH_HEAD head=$EXPECTED_SOURCE_HEAD count=$n guest=NOT_EXECUTED"
