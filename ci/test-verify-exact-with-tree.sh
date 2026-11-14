#!/usr/bin/env bash
set -euo pipefail
verifier="$(realpath "$1")"
tmp="$(mktemp -d)"
trap 'rm -rf -- "$tmp"' EXIT
cd "$tmp"
git init -q
git config user.email example@example.invalid
git config user.name 'Synthetic CI'
mkdir -p private-oreslang-source/dir
printf 'public synthetic fixture\n' > private-oreslang-source/dir/sample.atto
printf 'trusted control\n' > control.txt
git add .
git commit -qm 'synthetic tree'
sha="$(git rev-parse HEAD:private-oreslang-source)"
bash "$verifier" "$sha" 1 | grep -q '^WITH_EXACT_TREE_PASS'
rejects() {
  if bash "$verifier" "$@" > "$tmp/out" 2>&1; then echo 'unexpected success' >&2; exit 1; fi
  grep -q 'WITH_EXACT_TREE_REJECTED' "$tmp/out"
}
rejects "${sha%?}0" 1
rejects "$sha" 2
rejects 'bad' 1
rejects "$sha" 00
printf 'untracked\n' > private-oreslang-source/extra.txt
rejects "$sha" 1
rm private-oreslang-source/extra.txt
printf 'changed\n' > private-oreslang-source/dir/sample.atto
rejects "$sha" 1
git checkout -q -- private-oreslang-source/dir/sample.atto
ln -s ../control.txt private-oreslang-source/link.txt
git add private-oreslang-source/link.txt
git commit -qm 'symlink positive'
newsha="$(git rev-parse HEAD:private-oreslang-source)"
bash "$verifier" "$newsha" 2 | grep -q '^WITH_EXACT_TREE_PASS'
rejects "$sha" 1
printf 'WITH_EXACT_TREE_SYNTHETIC_TESTS_PASS\n'
