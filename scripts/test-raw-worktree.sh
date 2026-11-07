#!/usr/bin/env bash
set -euo pipefail
checker="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/verify-raw-worktree.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
cd "$tmp"
git init -q
git config user.email verifier@example.invalid
git config user.name Verifier
printf 'stable source\n' > source.atto
printf 'target\n' > target
ln -s target ref
chmod 644 source.atto target
git add .
git commit -qm baseline
head="$(git rev-parse HEAD)"
"$checker" "$head" > /dev/null
echo 'PASS: pristine exact tree'
printf 'modified\n' >> source.atto
if "$checker" "$head" >/dev/null 2>&1; then echo 'FAIL: modified bytes admitted'; exit 1; fi
printf 'stable source\n' > source.atto
echo 'PASS: modified bytes denied'
rm ref; printf 'target' > ref
if "$checker" "$head" >/dev/null 2>&1; then echo 'FAIL: replaced symlink admitted'; exit 1; fi
rm ref; ln -s target ref
echo 'PASS: symlink substitution denied'
echo bad > untracked.tmp
if "$checker" "$head" >/dev/null 2>&1; then echo 'FAIL: untracked file admitted'; exit 1; fi
rm untracked.tmp
echo 'PASS: untracked file denied'
git config filter.corrupt.smudge "sed 's/stable source/modified by smudge/'"
printf 'source.atto filter=corrupt\n' > .gitattributes
git add .gitattributes && git commit -qm attributes
head="$(git rev-parse HEAD)"
git rm --cached -q source.atto >/dev/null 2>&1 || true
# Recover index unchanged; explicitly substitute smudge bytes in working tree.
git reset -q --hard "$head"
printf 'modified by smudge\n' > source.atto
if "$checker" "$head" >/dev/null 2>&1; then echo 'FAIL: smudge bytes admitted'; exit 1; fi
echo 'PASS: smudge-transformed physical bytes denied'
git config --unset filter.corrupt.smudge
git reset -q --hard "$head"
printf 'stable source\n' > source.atto
"$checker" "$head" > /dev/null
echo 'PASS: complete raw-tree verifier recovery'
