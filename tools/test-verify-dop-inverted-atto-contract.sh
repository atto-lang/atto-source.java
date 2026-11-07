#!/usr/bin/env bash
# Source-free, no-Python mutation harness for the DOP exact-head fixture gate.
# Work only in a throwaway local Git repository, never the original checkout.
set -euo pipefail
root="$(pwd)"
scratch="$(mktemp -d)"
trap 'rm -rf "$scratch"' EXIT
mkdir -p "$scratch/repo/tools" "$scratch/repo/contracts/dop-inverted-v1"
for name in swap-reuse.atto removed-index-trap.atto positive-immutable-column-swap.atto; do
    cp -- "$root/contracts/dop-inverted-v1/$name" "$scratch/repo/contracts/dop-inverted-v1/$name"
done
cp -- "$root/tools/verify-dop-inverted-atto-contract.sh" "$scratch/repo/tools/verify-dop-inverted-atto-contract.sh"
git -C "$scratch/repo" init -q
git -C "$scratch/repo" -c user.name='DOP source gate' -c user.email='source-gate@example.invalid' \
    add contracts tools
git -C "$scratch/repo" -c user.name='DOP source gate' -c user.email='source-gate@example.invalid' \
    commit -qm 'synthetic source contract'
head="$(git -C "$scratch/repo" rev-parse HEAD)"
run_gate() {
    (cd "$scratch/repo" && EXPECTED_HEAD="$head" bash tools/verify-dop-inverted-atto-contract.sh) \
        >/dev/null 2>&1
}
reject() {
    local name="$1"
    if run_gate; then
        echo "FAIL: source admission mutation accepted: $name" >&2
        exit 1
    fi
}
if ! run_gate; then
    echo 'FAIL: unmodified three-fixture source contract rejected' >&2
    exit 1
fi

printf '\n// tampered source\n' >> "$scratch/repo/contracts/dop-inverted-v1/swap-reuse.atto"
reject 'on-disk fixture mutation'
git -C "$scratch/repo" reset --hard -q "$head"

printf '\n// staged tamper\n' >> "$scratch/repo/contracts/dop-inverted-v1/removed-index-trap.atto"
git -C "$scratch/repo" add contracts/dop-inverted-v1/removed-index-trap.atto
reject 'staged fixture mutation'
git -C "$scratch/repo" reset --hard -q "$head"

printf '\n# forged checker\n' >> "$scratch/repo/tools/verify-dop-inverted-atto-contract.sh"
if (cd "$scratch/repo" && export EXPECTED_HEAD="$head" && \
    git show HEAD:tools/verify-dop-inverted-atto-contract.sh | \
    bash) >/dev/null 2>&1; then
    echo 'FAIL: immutable committed checker accepted a rewritten on-disk checker' >&2
    exit 1
fi
git -C "$scratch/repo" reset --hard -q "$head"

rm "$scratch/repo/contracts/dop-inverted-v1/positive-immutable-column-swap.atto"
ln -s swap-reuse.atto "$scratch/repo/contracts/dop-inverted-v1/positive-immutable-column-swap.atto"
reject 'symlink substitution'
git -C "$scratch/repo" reset --hard -q "$head"

git -C "$scratch/repo" -c user.name='DOP source gate' -c user.email='source-gate@example.invalid' \
    commit --allow-empty -qm 'unrelated new head'
reject 'wrong expected checkout head'

printf 'PASS: DOP source admission 1 positive + 5 negative Git mutation cases\n'
