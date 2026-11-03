#!/usr/bin/env bash
# Raw exact-HEAD verification for private AttoLang compiler CI.
# Do not execute configured Git clean/smudge filters or read another org's Git tree.
set -euo pipefail
export LC_ALL=C

fail() { printf 'ATTO_RAW_SOURCE_DENIED: %s\n' "$*" >&2; exit 2; }
[[ -d .git ]] || fail "must execute from the standalone compiler checkout root"
[[ "$(git rev-parse --show-toplevel)" == "$PWD" ]] ||
  fail "checkout root mismatch"
expected_head="${ATTO_EXPECTED_HEAD:-}"
[[ "$expected_head" =~ ^[0-9a-f]{40}$ ]] || fail "missing exact source HEAD pin"
[[ "$(git rev-parse HEAD)" == "$expected_head" ]] || fail "source HEAD changed"
expected_tree="$(git rev-parse 'HEAD^{tree}')"
[[ "$(git write-tree)" == "$expected_tree" ]] ||
  fail "index differs from committed HEAD tree"

verified=0
while IFS= read -r -d '' entry; do
  metadata="${entry%%$'\t'*}"
  path="${entry#*$'\t'}"
  read -r mode expected_blob stage <<< "$metadata"
  [[ "$stage" == 0 ]] || fail "unmerged source index stage: $path"
  case "$mode" in
    100644|100755)
      [[ -f "$path" && ! -L "$path" ]] ||
        fail "tracked file missing or replaced by link: $path"
      actual_blob="$(git hash-object --no-filters -- "$path")" ||
        fail "cannot hash raw tracked file: $path"
      ;;
    120000)
      # Verify a tracked symlink's link text rather than reading its referent.
      [[ -L "$path" ]] || fail "tracked symlink changed type: $path"
      actual_blob="$(readlink -n -- "$path" | git hash-object --stdin)" ||
        fail "cannot hash tracked symlink: $path"
      ;;
    *)
      fail "unsupported tracked source index mode $mode: $path"
      ;;
  esac
  [[ "$actual_blob" == "$expected_blob" ]] ||
    fail "tracked raw blob differs from index: $path"
  verified=$((verified + 1))
done < <(git ls-files --stage -z)
[[ "$verified" -gt 0 ]] || fail "empty checked source tree"

# A new, untracked guest/compiler source is still input even if it is absent
# from the committed index. Build outputs under ignored target/ stay separate.
while IFS= read -r -d '' path; do
  case "$path" in
    *.atto|*.ores|*.java|*.toml|*.xml|*.yml|*.yaml)
      fail "untracked compiler/source/config input: $path" ;;
  esac
done < <(git ls-files --others --exclude-standard -z)

[[ "$(git rev-parse HEAD)" == "$expected_head" ]] || fail "source HEAD changed"
[[ "$(git write-tree)" == "$expected_tree" ]] || fail "source index changed"
printf 'ATTO_RAW_SOURCE_VERIFY_PASS tracked=%d head=%s tree=%s filters=NO\n' \
  "$verified" "$expected_head" "$expected_tree"
