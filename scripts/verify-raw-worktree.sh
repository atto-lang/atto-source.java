#!/usr/bin/env bash
# Verify that every tracked file's literal checkout bytes match HEAD/index.
# Never run a Git clean/smudge filter in this verifier.
set -euo pipefail
expected="${1:?usage: verify-raw-worktree.sh EXACT_40_CHARACTER_HEAD_SHA}"
if [[ ! "$expected" =~ ^[0-9a-f]{40}$ ]]; then
  echo 'EXPECTED must be an exact 40-character commit SHA' >&2
  exit 2
fi
root="$(git rev-parse --show-toplevel)"
cd "$root"
actual="$(git rev-parse --verify HEAD)"
[[ "$actual" == "$expected" ]] || { echo 'HEAD mismatch' >&2; exit 2; }
head_tree="$(git rev-parse 'HEAD^{tree}')"
index_tree="$(git write-tree)"
[[ "$index_tree" == "$head_tree" ]] || { echo 'index differs from exact HEAD tree' >&2; exit 2; }
count=0
while IFS= read -r -d '' item; do
  header="${item%%$'\t'*}"
  path="${item#*$'\t'}"
  [[ "$header" != "$item" ]] || { echo 'invalid index record' >&2; exit 2; }
  read -r mode sha stage <<< "$header"
  [[ "$stage" == 0 && "$sha" =~ ^[0-9a-f]{40}$ ]] || { echo 'unmerged or invalid index entry' >&2; exit 2; }
  case "$mode" in
    100644|100755)
      [[ -f "$path" && ! -L "$path" ]] || { echo "missing/replaced regular file: $path" >&2; exit 2; }
      if [[ "$mode" == 100755 ]]; then
        [[ -x "$path" ]] || { echo "executable bit lost: $path" >&2; exit 2; }
      else
        [[ ! -x "$path" ]] || { echo "unexpected executable bit: $path" >&2; exit 2; }
      fi
      got="$(git hash-object --no-filters -- "$path")"
      ;;
    120000)
      [[ -L "$path" ]] || { echo "symlink replaced: $path" >&2; exit 2; }
      got="$(readlink -n -- "$path" | git hash-object --stdin)"
      ;;
    *) echo "unsupported tracked file mode $mode: $path" >&2; exit 2 ;;
  esac
  [[ "$got" == "$sha" ]] || { echo "raw blob differs from index: $path" >&2; exit 2; }
  count=$((count+1))
done < <(git ls-files --stage -z)
[[ "$count" -gt 0 ]] || { echo 'empty tracked source tree' >&2; exit 2; }
# Admission occurs before compilation; even ignored untracked files can shadow inputs.
extra_bytes="$(git ls-files --others -z | wc -c)"
[[ "$extra_bytes" -eq 0 ]] || { echo 'untracked source files present' >&2; exit 2; }
echo "EXACT_TREE_RAW_WORKTREE_VERIFIED head=$actual tree=$head_tree tracked=$count"
